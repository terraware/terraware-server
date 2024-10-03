package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.DeliverableFilesRenamer
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminApplicationsController(
    private val applicationStore: ApplicationStore,
    private val deliverableFilesRenamer: DeliverableFilesRenamer,
) {
  private val log = perClassLogger()

  @PostMapping("/applications/cleanUpDrive")
  fun cleanUpApplicationDrive(
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val applications = applicationStore.fetchAll()
      applications.forEach {
        deliverableFilesRenamer.createOrUpdateGoogleDriveFolder(it.projectId, it.internalName)
      }
      redirectAttributes.successMessage = "Success!"
    } catch (e: Exception) {
      log.warn("Clean up application Google Drive failed", e)
      redirectAttributes.failureMessage = "Failed: ${e.message}"
    }

    return redirectToHome()
  }

  private fun redirectToHome() = "redirect:/admin"
}
