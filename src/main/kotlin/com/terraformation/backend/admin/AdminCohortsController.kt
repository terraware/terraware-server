package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.CohortModuleStore
import com.terraformation.backend.accelerator.db.CohortNotFoundException
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.DeliverableDueDateStore
import com.terraformation.backend.accelerator.db.DeliverableNotFoundException
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.log.perClassLogger
import java.time.LocalDate
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole(
    [GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin, GlobalRole.TFExpert, GlobalRole.ReadOnly]
)
@Validated
class AdminCohortsController(
    private val deliverableDueDateStore: DeliverableDueDateStore,
    private val deliverableStore: DeliverableStore,
    private val cohortStore: CohortStore,
    private val cohortModuleStore: CohortModuleStore,
    private val moduleStore: ModuleStore,
) {
  private val log = perClassLogger()

  @GetMapping("/cohorts")
  fun cohortsHome(model: Model): String {
    requirePermissions { readCohorts() }
    val cohorts = cohortStore.findAll()

    model.addAttribute("cohorts", cohorts)

    return "/admin/cohorts"
  }

  @GetMapping("/cohorts/{cohortId}")
  fun cohortView(
      model: Model,
      @PathVariable cohortId: CohortId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { readCohort(cohortId) }
    val cohort =
        try {
          cohortStore.fetchOneById(cohortId, CohortDepth.Cohort)
        } catch (e: CohortNotFoundException) {
          log.warn("Cohort not found", e)
          redirectAttributes.failureMessage = "Cohort not found: ${e.message}"
          return redirectToCohortHome()
        }

    val cohortModules = cohortModuleStore.fetch(cohortId)

    val modules = moduleStore.fetchAllModules()
    val allDeliverables = deliverableStore.fetchDeliverables().groupBy { it.moduleId }
    val moduleDeliverables = modules.associate { it.id to (allDeliverables[it.id] ?: emptyList()) }

    val moduleNames = modules.associate { it.id to "(${it.id}) ${it.name}" }

    val unassignedModules = modules.filter { module -> cohortModules.all { module.id != it.id } }

    model.addAttribute("canUpdateCohort", currentUser().canUpdateCohort(cohortId))
    model.addAttribute("cohort", cohort)
    model.addAttribute("cohortModules", cohortModules)
    model.addAttribute("moduleDeliverables", moduleDeliverables)
    model.addAttribute("moduleNames", moduleNames)
    model.addAttribute("unassignedModules", unassignedModules)
    model.addAttribute("canUpdateCohort", currentUser().canUpdateCohort(cohortId))

    return "/admin/cohortView"
  }

  @PostMapping("/cohorts/{cohortId}/deliverables")
  fun deliverablesRedirect(
      model: Model,
      @PathVariable cohortId: CohortId,
      @RequestParam deliverableId: DeliverableId,
  ): String = redirectToCohortDeliverable(cohortId, deliverableId)

  @GetMapping("/cohorts/{cohortId}/deliverables/{deliverableId}")
  fun viewDeliverable(
      model: Model,
      @PathVariable cohortId: CohortId,
      @PathVariable deliverableId: DeliverableId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { readAllDeliverables() }

    val cohort =
        try {
          cohortStore.fetchOneById(cohortId, CohortDepth.Project)
        } catch (e: CohortNotFoundException) {
          log.warn("Cohort not found", e)
          redirectAttributes.failureMessage = "Cohort not found: ${e.message}"
          return redirectToCohortHome()
        }

    val dueDateModel =
        try {
          deliverableDueDateStore
              .fetchDeliverableDueDates(cohortId = cohortId, deliverableId = deliverableId)
              .firstOrNull()
        } catch (e: Exception) {
          log.warn("Fetch deliverable due dates failed", e)
          redirectAttributes.failureMessage = "Error looking up deliverable due date: ${e.message}"
          return redirectToCohort(cohortId)
        }

    if (dueDateModel == null) {
      redirectAttributes.failureMessage =
          "Deliverable $deliverableId not associated with cohort $cohortId"
      return redirectToCohort(cohortId)
    }

    val cohortModule = cohortModuleStore.fetch(cohortId, moduleId = dueDateModel.moduleId).first()

    val deliverable =
        try {
          deliverableStore.fetchDeliverables(deliverableId).firstOrNull()
              ?: throw DeliverableNotFoundException(deliverableId)
        } catch (e: Exception) {
          log.warn("Fetch deliverable data failed", e)
          redirectAttributes.failureMessage = "Error looking up deliverable data: ${e.message}"
          return redirectToCohort(cohortId)
        }

    val submissions =
        deliverableStore
            .fetchDeliverableSubmissions(deliverableId = deliverableId)
            .filter { cohort.projectIds.contains(it.projectId) }
            .sortedBy { it.projectId }

    model.addAttribute("canManageDeliverables", currentUser().canManageDeliverables())
    model.addAttribute("cohort", cohort)
    model.addAttribute("cohortModule", cohortModule)
    model.addAttribute("deliverable", deliverable)
    model.addAttribute("dueDates", dueDateModel)
    model.addAttribute("submissions", submissions)

    return "/admin/cohortDeliverable"
  }

  @PostMapping("/cohorts/{cohortId}/deliverables/{deliverableId}")
  fun updateDeliverableDueDate(
      model: Model,
      @PathVariable cohortId: CohortId,
      @PathVariable deliverableId: DeliverableId,
      @RequestParam operation: String,
      @RequestParam projectId: ProjectId,
      @RequestParam dueDate: LocalDate?,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { manageDeliverables() }
    when (operation) {
      "upsert" -> {
        if (dueDate == null) {
          redirectAttributes.failureMessage = "Due date must be set for upsert"
          return redirectToCohortDeliverable(cohortId, deliverableId)
        }

        try {
          deliverableDueDateStore.upsertDeliverableProjectDueDate(
              deliverableId,
              projectId,
              dueDate,
          )
        } catch (e: Exception) {
          log.warn("Upsert deliverable due date failed", e)
          redirectAttributes.failureMessage = "Error updating deliverable due date: ${e.message}"
        }

        redirectAttributes.successMessage = "Successfully updated due date."
      }
      "remove" -> {
        try {
          deliverableDueDateStore.deleteDeliverableProjectDueDate(deliverableId, projectId)
        } catch (e: Exception) {
          log.warn("Delete deliverable due date failed", e)
          redirectAttributes.failureMessage = "Error deleting deliverable due date: ${e.message}"
        }

        redirectAttributes.successMessage = "Successfully deleted due date."
      }
      else -> {
        redirectAttributes.failureMessage = "Operation not recognized. "
      }
    }
    return redirectToCohortDeliverable(cohortId, deliverableId)
  }

  private fun redirectToCohort(cohortId: CohortId) = "redirect:/admin/cohorts/$cohortId"

  private fun redirectToCohortDeliverable(cohortId: CohortId, deliverableId: DeliverableId) =
      "redirect:/admin/cohorts/$cohortId/deliverables/$deliverableId"

  private fun redirectToCohortHome() = "redirect:/admin/cohorts"
}
