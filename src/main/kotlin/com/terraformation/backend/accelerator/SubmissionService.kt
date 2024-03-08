package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.DeliverableNotFoundException
import com.terraformation.backend.accelerator.db.ProjectDocumentSettingsNotConfiguredException
import com.terraformation.backend.accelerator.document.DropboxReceiver
import com.terraformation.backend.accelerator.document.GoogleDriveReceiver
import com.terraformation.backend.accelerator.document.SubmissionDocumentReceiver
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.records.SubmissionDocumentsRecord
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_DOCUMENT_SETTINGS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.DropboxWriter
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@Named
class SubmissionService(
    private val clock: InstantSource,
    private val dropboxWriter: DropboxWriter,
    private val dslContext: DSLContext,
    private val googleDriveWriter: GoogleDriveWriter,
) {
  private val log = perClassLogger()

  /**
   * Matches characters that aren't allowed in filenames.
   *
   * Based on the naming conventions section of the
   * [Win32 documentation](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file).
   */
  private val illegalFilenameCharacters = Regex("[<>:\"/\\\\|?*]")

  fun receiveDocument(
      inputStream: InputStream,
      originalName: String?,
      projectId: ProjectId,
      deliverableId: DeliverableId,
      description: String,
      contentType: String
  ): SubmissionDocumentId {
    requirePermissions { createSubmission(projectId) }

    val deliverableRecord =
        dslContext.selectFrom(DELIVERABLES).where(DELIVERABLES.ID.eq(deliverableId)).fetchOne()
            ?: throw DeliverableNotFoundException(deliverableId)
    val projectDocumentSettings =
        dslContext
            .selectFrom(PROJECT_DOCUMENT_SETTINGS)
            .where(PROJECT_DOCUMENT_SETTINGS.PROJECT_ID.eq(projectId))
            .fetchOne() ?: throw ProjectDocumentSettingsNotConfiguredException(projectId)

    val now = clock.instant()
    val currentDateUtc = LocalDate.ofInstant(now, ZoneOffset.UTC)

    // Use the file extension from the original filename.
    val extension = sanitizeForFilename(originalName?.substringAfterLast('.')?.let { ".$it" } ?: "")

    // Filenames follow a fixed format.
    val rawFileName =
        listOf(
                deliverableRecord.name!!,
                currentDateUtc.toString(),
                projectDocumentSettings.fileNaming!!,
                description,
            )
            .joinToString("_")

    // Some of the components of the name can have characters that aren't allowed in filenames.
    val baseName = sanitizeForFilename(rawFileName)
    val fileName = baseName + extension

    val receiver: SubmissionDocumentReceiver =
        if (deliverableRecord.isSensitive == true) {
          DropboxReceiver(dropboxWriter, projectDocumentSettings.dropboxFolderPath!!)
        } else {
          GoogleDriveReceiver(googleDriveWriter, projectDocumentSettings.googleFolderUrl!!)
        }

    val storedFile = receiver.upload(inputStream, fileName, contentType)

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
            .also { it.attach(dslContext.configuration()) }

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
        receiver.rename(storedFile, submissionDocument.name!!)
      }

      return submissionDocument.id!!
    } catch (e: Exception) {
      log.error("Error while recording uploaded document", e)

      receiver.delete(storedFile)

      throw e
    }
  }

  /**
   * Replaces characters that aren't safe to include in filenames with hyphens.
   *
   * The list of unsafe characters is from
   * [Microsoft](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file).
   */
  private fun sanitizeForFilename(fileName: String): String {
    return fileName.replace(illegalFilenameCharacters, "-")
  }
}
