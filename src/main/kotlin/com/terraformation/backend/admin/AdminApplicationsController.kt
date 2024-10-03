package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ProjectGoogleDriveCleanupEvent
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import org.springframework.context.ApplicationEventPublisher
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
    private val eventPublisher: ApplicationEventPublisher,
) {
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

    applications.forEach {
      eventPublisher.publishEvent(ProjectGoogleDriveCleanupEvent(it.projectId, it.internalName))
    }

    redirectAttributes.successMessage =
        "Dispatched ${applications.size} clean up jobs. " +
            "Please wait a few minutes for all the jobs to complete. Check logs for results."

    return redirectToHome()
  }

  private fun redirectToHome() = "redirect:/admin"
}
