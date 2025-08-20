package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.DeliverableNotFoundException
import com.terraformation.backend.accelerator.db.ProjectDocumentSettingsNotConfiguredException
import com.terraformation.backend.accelerator.db.ProjectDocumentStorageFailedException
import com.terraformation.backend.accelerator.db.SubmissionDocumentNotFoundException
import com.terraformation.backend.accelerator.document.DropboxReceiver
import com.terraformation.backend.accelerator.document.GoogleDriveReceiver
import com.terraformation.backend.accelerator.document.SubmissionDocumentReceiver
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadFailedEvent
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadFailedEvent.FailureReason
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.records.ProjectAcceleratorDetailsRecord
import com.terraformation.backend.db.accelerator.tables.records.SubmissionDocumentsRecord
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.DropboxWriter
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException

@Named
class SubmissionService(
    private val clock: InstantSource,
    private val config: TerrawareServerConfig,
    private val dropboxWriter: DropboxWriter,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val googleDriveWriter: GoogleDriveWriter,
) {
  companion object {
    /**
     * Longest name we'll use for an uploaded file, including extension. This should be less than
     * the maximum filename length on any of the file stores or common local filesystems by at least
     * a few characters so there's room to add a numeric suffix to the base name in case of filename
     * collisions.
     */
    const val MAX_FILENAME_LENGTH = 250

    /** Maximum number of characters allowed in a filename extension, including leading period. */
    const val MAX_EXTENSION_LENGTH = 10

    private val log = perClassLogger()
  }

  fun receiveDocument(
      inputStream: InputStream,
      originalName: String?,
      projectId: ProjectId,
      deliverableId: DeliverableId,
      description: String,
      contentType: String,
  ): SubmissionDocumentId {
    requirePermissions { createSubmission(projectId) }

    val deliverableRecord =
        dslContext.selectFrom(DELIVERABLES).where(DELIVERABLES.ID.eq(deliverableId)).fetchOne()
            ?: throw DeliverableNotFoundException(deliverableId)

    val isSensitive = deliverableRecord.isSensitive == true
    val documentStore =
        if (isSensitive) {
          DocumentStore.Dropbox
        } else {
          DocumentStore.Google
        }

    val projectAcceleratorDetails =
        with(PROJECT_ACCELERATOR_DETAILS) {
          dslContext
              .select(DROPBOX_FOLDER_PATH, FILE_NAMING, GOOGLE_FOLDER_URL)
              .from(PROJECT_ACCELERATOR_DETAILS)
              .where(PROJECT_ID.eq(projectId))
              .fetchOneInto(ProjectAcceleratorDetailsRecord::class.java)
        }

    val applicationInternalName =
        with(APPLICATIONS) {
          dslContext
              .select(INTERNAL_NAME)
              .from(APPLICATIONS)
              .where(PROJECT_ID.eq(projectId))
              .fetchOne(INTERNAL_NAME)
        }

    if (projectAcceleratorDetails == null && applicationInternalName == null) {
      uploadFailed(
          deliverableId,
          projectId,
          FailureReason.ProjectNotConfigured,
          documentStore,
          originalName,
      )
    }

    val now = clock.instant()
    val currentDateUtc = LocalDate.ofInstant(now, ZoneOffset.UTC)

    // Use the file extension from the original filename.
    val extension =
        sanitizeForFilename(originalName?.substringAfterLast('.')?.let { ".$it" } ?: "")
            .take(MAX_EXTENSION_LENGTH)

    // Filenames follow a fixed format.
    val fileNaming =
        projectAcceleratorDetails?.fileNaming
            ?: applicationInternalName
            ?: uploadFailed(
                deliverableId,
                projectId,
                FailureReason.FileNamingNotConfigured,
                documentStore,
                originalName,
            )

    val rawFileName =
        listOf(
                deliverableRecord.name!!,
                currentDateUtc.toString(),
                fileNaming,
                description,
            )
            .joinToString("_")

    // Some of the components of the name can have characters that aren't allowed in filenames, and
    // filenames can't be infinitely long.
    val baseName = sanitizeForFilename(rawFileName).take(MAX_FILENAME_LENGTH - extension.length)
    val fileName = baseName + extension

    // This is a String for Dropbox and a URI for Google
    val folder: Any =
        if (isSensitive) {
          projectAcceleratorDetails?.dropboxFolderPath
        } else {
          projectAcceleratorDetails?.googleFolderUrl
              ?: createGoogleDriveFolder(projectId, fileNaming)
        }
            ?: uploadFailed(
                deliverableId,
                projectId,
                FailureReason.FolderNotConfigured,
                documentStore,
                originalName,
            )

    val receiver: SubmissionDocumentReceiver =
        if (isSensitive) {
          DropboxReceiver(dropboxWriter, folder as String)
        } else {
          GoogleDriveReceiver(googleDriveWriter, folder as URI)
        }

    val storedFile =
        try {
          receiver.upload(inputStream, fileName, contentType)
        } catch (e: Exception) {
          uploadFailed(
              deliverableId,
              projectId,
              FailureReason.CouldNotUpload,
              documentStore,
              originalName,
              folder.toString(),
              fileName,
              e,
          )
        }

    val submissionId =
        with(SUBMISSIONS) {
          dslContext
              .insertInto(SUBMISSIONS)
              .set(PROJECT_ID, projectId)
              .set(DELIVERABLE_ID, deliverableId)
              .set(SUBMISSION_STATUS_ID, SubmissionStatus.InReview)
              .set(CREATED_BY, currentUser().userId)
              .set(CREATED_TIME, now)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, now)
              .onConflict(PROJECT_ID, DELIVERABLE_ID)
              .doUpdate()
              .set(SUBMISSION_STATUS_ID, SubmissionStatus.InReview)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, now)
              .returning(ID)
              .fetchOne(ID)
        }

    // Google allows multiple files to have the same name, but we want unique names. The unique
    // constraint on project ID and name on the `submission_documents` table will prevent us from
    // recording colliding names; if we get a collision, we try to find an unused numeric suffix
    // to add to the filename.
    val submissionDocument =
        SubmissionDocumentsRecord(
                submissionId = submissionId,
                documentStoreId = receiver.documentStore,
                createdBy = currentUser().userId,
                createdTime = now,
                name = storedFile.storedName,
                description = description,
                location = storedFile.location,
                originalName = originalName,
                projectId = projectId,
            )
            .attach(dslContext)

    try {
      try {
        // On collision, don't leave the database connection in an error state.
        dslContext.transaction { _ -> submissionDocument.insert() }
      } catch (e: DuplicateKeyException) {
        // If we have multiple colliding uploads at once (e.g., if the client is doing parallel
        // upload requests for a bunch of photos), allocating suffixes will cause a race. Avoid it
        // by locking the row with the original name.
        dslContext.transaction { _ ->
          dslContext
              .selectOne()
              .from(SUBMISSION_DOCUMENTS)
              .where(SUBMISSION_DOCUMENTS.NAME.eq(fileName))
              .and(SUBMISSION_DOCUMENTS.PROJECT_ID.eq(projectId))
              .forUpdate()
              .fetch()

          // Find the existing suffixed filenames, if any, so we can bump the suffix number.
          val namePattern = DSL.escape(baseName, '\\') + "\\_%" + DSL.escape(extension, '\\')

          val lastSuffixNumber =
              dslContext
                  .select(SUBMISSION_DOCUMENTS.NAME)
                  .from(SUBMISSION_DOCUMENTS)
                  .where(SUBMISSION_DOCUMENTS.PROJECT_ID.eq(projectId))
                  .and(SUBMISSION_DOCUMENTS.NAME.like(namePattern))
                  .fetch(SUBMISSION_DOCUMENTS.NAME.asNonNullable())
                  .map { it.substring(baseName.length + 1).substringBefore('.') }
                  .mapNotNull { it.toIntOrNull() }
                  .maxOrNull()

          val nextSuffixNumber =
              if (lastSuffixNumber != null) {
                lastSuffixNumber + 1
              } else {
                // Suffixes should start at 2, per product requirements.
                2
              }

          val newFileName = "${baseName}_${nextSuffixNumber}$extension"

          submissionDocument.name = newFileName

          submissionDocument.insert()
        }
      }

      if (submissionDocument.name != storedFile.storedName) {
        try {
          receiver.rename(storedFile, submissionDocument.name!!)
        } catch (e: Exception) {
          uploadFailed(
              deliverableId,
              projectId,
              FailureReason.CouldNotRename,
              documentStore,
              originalName,
              folder.toString(),
              submissionDocument.name,
              e,
          )
        }
      }

      val documentId = submissionDocument.id!!

      eventPublisher.publishEvent(
          DeliverableDocumentUploadedEvent(deliverableId, documentId, projectId)
      )

      return documentId
    } catch (e: Exception) {
      log.error("Error while recording uploaded document", e)

      receiver.delete(storedFile)

      throw e
    }
  }

  /**
   * Returns a URL that can be used by external clients to read a submission document, subject to
   * the document store's access controls.
   */
  fun getExternalUrl(deliverableId: DeliverableId, documentId: SubmissionDocumentId): URI {
    requirePermissions { readSubmissionDocument(documentId) }

    val (documentStore, location) =
        dslContext
            .select(
                SUBMISSION_DOCUMENTS.DOCUMENT_STORE_ID,
                SUBMISSION_DOCUMENTS.LOCATION.asNonNullable(),
            )
            .from(SUBMISSION_DOCUMENTS)
            .where(SUBMISSION_DOCUMENTS.ID.eq(documentId))
            .and(SUBMISSION_DOCUMENTS.submissions().DELIVERABLE_ID.eq(deliverableId))
            .fetchOne() ?: throw SubmissionDocumentNotFoundException(documentId)

    return when (documentStore!!) {
      DocumentStore.Dropbox -> dropboxWriter.shareFile(location)
      DocumentStore.External -> URI.create(location)
      DocumentStore.Google -> googleDriveWriter.shareFile(location)
    }
  }

  /**
   * Creates a new folder on Google Drive for a project that doesn't have one yet. This will be the
   * case for new applicants. Records the folder's URL in the project accelerator details.
   *
   * @return The URL of the new folder, or null if it couldn't be created.
   */
  private fun createGoogleDriveFolder(projectId: ProjectId, fileNaming: String): URI? {
    val folderName = fileNaming + INTERNAL_FOLDER_SUFFIX
    val parentFolderId = config.accelerator.applicationGoogleFolderId ?: return null
    val driveId = googleDriveWriter.getDriveIdForFile(parentFolderId)

    val newFolderId =
        googleDriveWriter.findOrCreateFolders(driveId, parentFolderId, listOf(folderName))
    val newFolderUrl = googleDriveWriter.shareFile(newFolderId)

    with(PROJECT_ACCELERATOR_DETAILS) {
      dslContext
          .insertInto(PROJECT_ACCELERATOR_DETAILS)
          .set(PROJECT_ID, projectId)
          .set(GOOGLE_FOLDER_URL, newFolderUrl)
          .onConflict(PROJECT_ID)
          .doUpdate()
          .set(GOOGLE_FOLDER_URL, newFolderUrl)
          .execute()
    }

    return newFolderUrl
  }

  /** Publishes an event and throws an exception when an upload fails. */
  private fun uploadFailed(
      deliverableId: DeliverableId,
      projectId: ProjectId,
      reason: FailureReason,
      documentStore: DocumentStore,
      originalName: String?,
      documentStoreFolder: String? = null,
      fileName: String? = null,
      exception: Exception? = null,
  ): Nothing {
    eventPublisher.publishEvent(
        DeliverableDocumentUploadFailedEvent(
            deliverableId,
            projectId,
            reason,
            documentStore,
            originalName,
            documentStoreFolder,
            fileName,
            exception,
        )
    )

    if (exception != null) {
      throw ProjectDocumentStorageFailedException(projectId, cause = exception)
    } else {
      throw ProjectDocumentSettingsNotConfiguredException(projectId)
    }
  }
}
