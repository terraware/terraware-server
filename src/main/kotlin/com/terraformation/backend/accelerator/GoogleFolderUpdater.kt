package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.GoogleDriveWriter
import java.net.URI
import javax.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/** Event listeners for updating Google Drive folder when the names are changed */
@Named
class GoogleFolderUpdater(
    private val applicationStore: ApplicationStore,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val googleDriveWriter: GoogleDriveWriter,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val systemUser: SystemUser,
) {

  @EventListener
  fun on(event: ApplicationInternalNameUpdatedEvent) {
    systemUser.run {
      val application = applicationStore.fetchOneById(event.applicationId)
      val projectDetails = projectAcceleratorDetailsStore.fetchOneById(application.projectId)

      if (setOf(ApplicationStatus.NotSubmitted, ApplicationStatus.FailedPreScreen)
          .contains(application.status)) {
        if (projectDetails.googleFolderUrl != null) {
          renameFolder(projectDetails.googleFolderUrl, application.internalName)
        } else {
          createGoogleDriveFolder(application.projectId, application.internalName)
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
