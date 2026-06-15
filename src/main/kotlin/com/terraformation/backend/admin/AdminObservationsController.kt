package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.ObservationStore
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminObservationsController(
    private val observationService: ObservationService,
    private val observationStore: ObservationStore,
) {
  private val log = perClassLogger()

  @GetMapping("/observations")
  fun getAdminObservationsHome(): String {
    return "/admin/observations"
  }

  @PostMapping("/deleteObservation")
  fun adminDeleteObservation(
      @RequestParam observationId: ObservationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      observationService.deleteObservation(observationId)
      redirectAttributes.successMessage = "Deleted observation $observationId."
    } catch (e: Exception) {
      log.error("Failed to delete observation $observationId", e)
      redirectAttributes.failureMessage = "Failed to delete observation: $e"
    }

    return redirectToObservationsHome()
  }

  @PostMapping("/deleteIncompletePlots")
  fun adminDeleteIncompletePlots(
      @RequestParam observationId: ObservationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      observationStore.deleteIncompletePlots(observationId)
      redirectAttributes.successMessage =
          "Deleted incomplete plots from observation $observationId."
    } catch (e: Exception) {
      log.error("Failed to delete incomplete plots from observation $observationId", e)
      redirectAttributes.failureMessage = "Failed to delete incomplete plots from observation: $e"
    }

    return redirectToObservationsHome()
  }

  private fun redirectToObservationsHome() = "redirect:/admin/observations"
}
