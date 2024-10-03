package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.DeliverableFilesRenamer
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
    val applications =
        try {
          applicationStore.fetchAll()
        } catch (e: Exception) {
          redirectAttributes.failureMessage = "Failed to fetch applications: ${e.message}"
          return redirectToHome()
        }

    runBlocking {
      applications.forEach {
        async {
          try {
            deliverableFilesRenamer.createOrUpdateGoogleDriveFolder(it.projectId, it.internalName)
          } catch (e: Exception) {
            log.warn("Failed to cleanup Google Drive folder for project ${it.projectId}", e)
          }
        }
      }
    }

    redirectAttributes.successMessage =
        "Launched ${applications.size} clean up jobs. " +
            "Please wait a few minutes for all the jobs to complete. Check logs for results."

    return redirectToHome()
  }

  private fun redirectToHome() = "redirect:/admin/"
}
