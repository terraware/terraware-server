package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.ObservationStore
import org.jooq.DSLContext
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
    private val dslContext: DSLContext,
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
      @RequestParam retainObservationIds: List<ObservationId>?,
      @RequestParam dryRun: Boolean,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val (retainedPlotIds, deletedPlotIds) =
          observationStore.deleteIncompletePlots(
              observationId,
              retainObservationIds ?: emptyList(),
              dryRun,
          )

      val plotNumbersById =
          with(MONITORING_PLOTS) {
            dslContext
                .select(ID, PLOT_NUMBER)
                .from(MONITORING_PLOTS)
                .where(ID.`in`(retainedPlotIds + deletedPlotIds))
                .fetchMap(ID.asNonNullable(), PLOT_NUMBER.asNonNullable())
          }

      fun sortedPlotNumberList(ids: List<MonitoringPlotId>) =
          ids.map { plotNumbersById[it] ?: -1 }.sorted().joinToString()

      redirectAttributes.successMessage =
          if (dryRun) {
            "Dry run results for observation $observationId"
          } else {
            "Deleted incomplete plots from observation $observationId"
          }
      redirectAttributes.successDetails =
          listOf(
              "Retain plots: " + sortedPlotNumberList(retainedPlotIds),
              "Delete plots: " + sortedPlotNumberList(deletedPlotIds),
          )
    } catch (e: Exception) {
      log.error("Failed to delete incomplete plots from observation $observationId", e)
      redirectAttributes.failureMessage = "Failed to delete incomplete plots from observation: $e"
    }

    return redirectToObservationsHome()
  }

  @PostMapping("/mergeObservations")
  fun adminMergeObservations(
      @RequestParam sourceObservationId: ObservationId,
      @RequestParam targetObservationId: ObservationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      observationService.mergeObservations(sourceObservationId, targetObservationId)
      redirectAttributes.successMessage =
          "Merged observation $sourceObservationId into $targetObservationId."
    } catch (e: Exception) {
      log.error("Failed to merge observation $sourceObservationId into $targetObservationId", e)
      redirectAttributes.failureMessage =
          "Failed to merge observation $sourceObservationId into $targetObservationId: $e"
    }

    return redirectToObservationsHome()
  }

  private fun redirectToObservationsHome() = "redirect:/admin/observations"
}
