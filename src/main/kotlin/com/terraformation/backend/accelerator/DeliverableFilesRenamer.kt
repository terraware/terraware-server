package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionDocumentsDao
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.GoogleDriveWriter
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/** Event listeners for updating deliverable files when file namings are changed */
@Named
class DeliverableFilesRenamer(
    private val applicationStore: ApplicationStore,
    private val config: TerrawareServerConfig,
    private val deliverableStore: DeliverableStore,
    private val dslContext: DSLContext,
    private val googleDriveWriter: GoogleDriveWriter,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val submissionDocumentsDao: SubmissionDocumentsDao,
    private val systemUser: SystemUser,
) {

  @EventListener
  fun on(event: ApplicationInternalNameUpdatedEvent) {
    systemUser.run {
      val application = applicationStore.fetchOneById(event.applicationId)

      if (setOf(ApplicationStatus.NotSubmitted, ApplicationStatus.FailedPreScreen)
          .contains(application.status)) {
        createOrUpdateGoogleDriveFolder(application.projectId, application.internalName)
      }
    }
  }

  fun createOrUpdateGoogleDriveFolder(projectId: ProjectId, fileNaming: String) {
    val projectDetails = projectAcceleratorDetailsStore.fetchOneById(projectId)
    val folderUrl =
        if (projectDetails.googleFolderUrl != null) {
          renameFolder(projectDetails.googleFolderUrl, fileNaming)
          projectDetails.googleFolderUrl
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

    val folderId = googleDriveWriter.getFileIdForFolderUrl(folderUrl)
    val allDeliverables = listOf(cohortDeliverables, applicationDeliverables).flatten()

    allDeliverables.forEach { deliverable ->
      // Use a map to keep track of in use fileNames. Because this is a sequential operation, we
      // should not run into race conditions that we see in Submission Service
      val nextSuffix = mutableMapOf<String, Int>()

      deliverable.documents.forEach {
        // Recreate the proper filename by the document model
        val extension =
            sanitizeForFilename(
                it.originalName?.substringAfterLast('.')?.let { ext -> ".$ext" } ?: "")
        val createdDateUtc = LocalDate.ofInstant(it.createdTime, ZoneOffset.UTC)
        val rawFileName =
            listOf(
                    deliverable.name,
                    createdDateUtc.toString(),
                    fileNaming,
                    it.description,
                )
                .joinToString("_")
        val baseName = sanitizeForFilename(rawFileName)

        val suffix =
            if (nextSuffix.keys.contains(baseName)) {
              val suffixNumber = nextSuffix[baseName] ?: 1
              nextSuffix[baseName] = suffixNumber + 1
              "_$suffixNumber"
            } else {
              nextSuffix[baseName] = 2
              ""
            }

        val fileName = baseName + suffix + extension
        val documentRow = submissionDocumentsDao.fetchOneById(it.id)!!
        val fileId = documentRow.location!!

        googleDriveWriter.renameFile(fileId, fileName)
        googleDriveWriter.moveFile(fileId, folderId)
        submissionDocumentsDao.update(documentRow.copy(name = fileName))
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
