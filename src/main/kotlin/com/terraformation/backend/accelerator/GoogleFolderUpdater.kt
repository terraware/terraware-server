package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectFileNamingUpdatedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.file.GoogleDriveWriter
import java.net.URI
import javax.inject.Named
import org.springframework.context.event.EventListener

/** Event listeners for updating Google Drive folder when the names are changed */
@Named
class GoogleFolderUpdater(
    private val applicationStore: ApplicationStore,
    private val googleDriveWriter: GoogleDriveWriter,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val systemUser: SystemUser,
) {

  @EventListener
  fun on(event: ApplicationInternalNameUpdatedEvent) {
    systemUser.run {
      val application = applicationStore.fetchOneById(event.applicationId)
      val projectDetails = projectAcceleratorDetailsStore.fetchOneById(application.projectId)

      // If a project has a Google folder, but doesn't have a file naming set up
      if (projectDetails.googleFolderUrl != null && projectDetails.fileNaming == null) {
        renameFolder(projectDetails.googleFolderUrl, application.internalName)
      }
    }
  }

  @EventListener
  fun on(event: ParticipantProjectFileNamingUpdatedEvent) {
    systemUser.run {
      val projectDetails = projectAcceleratorDetailsStore.fetchOneById(event.projectId)
      if (projectDetails.googleFolderUrl != null && projectDetails.fileNaming != null) {
        renameFolder(projectDetails.googleFolderUrl, projectDetails.fileNaming)
      }
    }
  }

  private fun renameFolder(folderUrl: URI, fileNaming: String) {
    val folderName = fileNaming + INTERNAL_FOLDER_SUFFIX
    val fileId = googleDriveWriter.getFileIdForFolderUrl(folderUrl)
    googleDriveWriter.renameFile(fileId, folderName)
  }
}
