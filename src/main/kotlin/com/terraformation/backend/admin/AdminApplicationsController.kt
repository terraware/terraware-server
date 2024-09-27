package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminApplicationsController(
    private val applicationStore: ApplicationStore,
    private val eventPublisher: ApplicationEventPublisher
) {
  private val log = perClassLogger()

  @PostMapping("/applications/configureFolders")
  fun configureFoldersForApplications(redirectAttributes: RedirectAttributes): String {
    val applications = applicationStore.fetchAll()

    applications.forEach {
      requirePermissions { updateProjectDocumentSettings(it.projectId) }
      eventPublisher.publishEvent(ApplicationInternalNameUpdatedEvent(it.id))
    }

    redirectAttributes.successMessage =
        "Successfully emitted request. Please allow a few minutes for the process to complete."
    return redirectToAdmin()
  }

  private fun redirectToAdmin(): String {
    return "redirect:/admin/"
  }
}
