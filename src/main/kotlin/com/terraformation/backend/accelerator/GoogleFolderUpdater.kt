package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ApplicationStatus
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

      // If a project has a Google folder, and is still in pre-screen
      if (projectDetails.googleFolderUrl != null &&
          setOf(ApplicationStatus.NotSubmitted, ApplicationStatus.FailedPreScreen)
              .contains(application.status)) {
        renameFolder(projectDetails.googleFolderUrl, application.internalName)
      }
    }
  }

  private fun renameFolder(folderUrl: URI, fileNaming: String) {
    val folderName = fileNaming + INTERNAL_FOLDER_SUFFIX
    val fileId = googleDriveWriter.getFileIdForFolderUrl(folderUrl)
    googleDriveWriter.renameFile(fileId, folderName)
  }
}
