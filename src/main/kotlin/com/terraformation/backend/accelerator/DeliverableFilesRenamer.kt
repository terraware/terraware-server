package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionDocumentsDao
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener

/** Event listeners for updating deliverable files when file namings are changed */
@Named
class DeliverableFilesRenamer(
    private val applicationStore: ApplicationStore,
    private val config: TerrawareServerConfig,
    private val deliverableStore: DeliverableStore,
    private val dslContext: DSLContext,
    private val googleDriveWriter: GoogleDriveWriter,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val submissionDocumentsDao: SubmissionDocumentsDao,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: ApplicationInternalNameUpdatedEvent) {
    systemUser.run {
      val application = applicationStore.fetchOneById(event.applicationId)

      if (
          setOf(ApplicationStatus.NotSubmitted, ApplicationStatus.FailedPreScreen)
              .contains(application.status)
      ) {
        createOrUpdateGoogleDriveFolder(application.projectId, application.internalName)
      }
    }
  }

  private fun createOrUpdateGoogleDriveFolder(projectId: ProjectId, fileNaming: String) {
    val projectDetails = projectAcceleratorDetailsService.fetchOneById(projectId)
    val folderUrl =
        if (projectDetails.googleFolderUrl != null) {
          try {
            renameFolder(projectDetails.googleFolderUrl, fileNaming)
            projectDetails.googleFolderUrl
          } catch (e: IllegalArgumentException) {
            // Google url is invalid. Create a new one
            log.warn("Project $projectId had an invalid Google Drive URL. Recreating a new drive. ")
            createGoogleDriveFolder(projectId, fileNaming)
                ?: throw RuntimeException("Failed to create a Google Drive folder")
          }
        } else {
          createGoogleDriveFolder(projectId, fileNaming)
              ?: throw RuntimeException("Failed to create a Google Drive folder")
        }

    renameAndMoveDeliverableDocuments(projectId, fileNaming, folderUrl)
  }

  private fun renameAndMoveDeliverableDocuments(
      projectId: ProjectId,
      fileNaming: String,
      folderUrl: URI,
  ) {
    val cohortDeliverables =
        deliverableStore.fetchDeliverableSubmissions(projectId = projectId).filter {
          it.documents.isNotEmpty()
        }

    val applicationDeliverables =
        applicationStore.fetchApplicationDeliverables(projectId = projectId).filter {
          it.documents.isNotEmpty()
        }

    val folderId =
        try {
          googleDriveWriter.getFileIdForFolderUrl(folderUrl)
        } catch (e: Exception) {
          log.warn("Folder $folderUrl is not a valid Google drive")
          null
        }
    val allDeliverables = listOf(cohortDeliverables, applicationDeliverables).flatten()

    allDeliverables.forEach { deliverable ->
      deliverable.documents
          .filter { it.documentStore == DocumentStore.Google } // only rename Google files
          .forEach { existing ->
            // Move the document file to the correct folder
            val documentRow = submissionDocumentsDao.fetchOneById(existing.id)!!
            val fileId = documentRow.location!!

            folderId?.let {
              try {
                googleDriveWriter.moveFile(fileId, it)
              } catch (e: Exception) {
                log.warn("Failed to move file $fileId into drive $folderUrl")
              }
            }

            // Recreate the proper filename by the document model
            val extension =
                sanitizeForFilename(
                    existing.originalName?.substringAfterLast('.')?.let { ".$it" } ?: ""
                )

            val createdDateUtc = LocalDate.ofInstant(existing.createdTime, ZoneOffset.UTC)
            val rawFileName =
                listOf(
                        deliverable.name,
                        createdDateUtc.toString(),
                        fileNaming,
                        existing.description,
                    )
                    .joinToString("_")

            val baseName = sanitizeForFilename(rawFileName)

            // Only update if name is different.
            if (!(existing.name.startsWith(baseName) && existing.name.endsWith(extension))) {
              // Find the existing suffixed filenames, if any, so we can bump the suffix number.
              val namePattern = DSL.escape(baseName, '\\') + "\\_%" + DSL.escape(extension, '\\')

              val allMatchingNames =
                  dslContext
                      .select(SUBMISSION_DOCUMENTS.NAME)
                      .from(SUBMISSION_DOCUMENTS)
                      .where(SUBMISSION_DOCUMENTS.PROJECT_ID.eq(projectId))
                      .and(
                          DSL.or(
                              SUBMISSION_DOCUMENTS.NAME.like(namePattern),
                              SUBMISSION_DOCUMENTS.NAME.eq("$baseName$extension"),
                          )
                      )
                      .fetch(SUBMISSION_DOCUMENTS.NAME.asNonNullable())

              val suffix =
                  if (allMatchingNames.isEmpty()) {
                    // First entry with this name, no suffix required
                    ""
                  } else {
                    val maxSuffix =
                        allMatchingNames
                            .map { name ->
                              name.substring(baseName.length + 1).substringBefore('.')
                            }
                            .mapNotNull { suffix -> suffix.toIntOrNull() }
                            .maxOrNull()
                    val nextSuffix = maxSuffix?.let { it + 1 } ?: 2
                    "_${nextSuffix}"
                  }

              val fileName = baseName + suffix + extension

              try {
                googleDriveWriter.renameFile(fileId, fileName)
                submissionDocumentsDao.update(documentRow.copy(name = fileName))
              } catch (e: Exception) {
                log.warn("Failed to rename file $fileId")
              }
            }
          }
    }
  }

  private fun renameFolder(folderUrl: URI, fileNaming: String) {
    val folderName = fileNaming + INTERNAL_FOLDER_SUFFIX
    val fileId = googleDriveWriter.getFileIdForFolderUrl(folderUrl)
    googleDriveWriter.renameFile(fileId, folderName)
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
}
