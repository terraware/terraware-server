package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.CohortNotFoundException
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.DeliverableDueDateStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.accelerator.model.CohortModuleDepth
import com.terraformation.backend.accelerator.model.CohortModuleModel
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.log.perClassLogger
import java.time.LocalDate
import org.springframework.dao.DataIntegrityViolationException
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
    [GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin, GlobalRole.TFExpert, GlobalRole.ReadOnly])
@Validated
class AdminCohortsController(
    private val deliverableDueDateStore: DeliverableDueDateStore,
    private val deliverableStore: DeliverableStore,
    private val cohortStore: CohortStore,
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
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { readCohort(cohortId) }
    val cohort =
        try {
          cohortStore.fetchOneById(cohortId, CohortDepth.Cohort, CohortModuleDepth.Module)
        } catch (e: CohortNotFoundException) {
          log.warn("Cohort not found", e)
          redirectAttributes.failureMessage = "Cohort not found: ${e.message}"
          return redirectToCohortHome()
        }

    val cohortModules = cohort.modules.map { it.moduleId }

    val modules = moduleStore.fetchAllModules().associateBy { it.id }

    model.addAttribute("cohort", cohort)
    model.addAttribute("cohortModules", cohortModules)
    model.addAttribute("modules", modules)
    model.addAttribute("canUpdateCohort", currentUser().canUpdateCohort(cohortId))

    return "/admin/cohortView"
  }

  @PostMapping("/cohorts/{cohortId}/addModule")
  fun addModule(
      model: Model,
      @PathVariable cohortId: CohortId,
      @RequestParam moduleId: ModuleId,
      @RequestParam title: String,
      @RequestParam startDate: LocalDate,
      @RequestParam endDate: LocalDate,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { updateCohort(cohortId) }
    try {
      cohortStore.update(cohortId) {
        val updatedModules =
            it.modules.plus(CohortModuleModel(cohortId, moduleId, title, startDate, endDate))
        it.copy(modules = updatedModules)
      }
      redirectAttributes.successMessage = "Cohort module added."
    } catch (e: Exception) {
      log.warn("Add cohort module failed")
      redirectAttributes.failureMessage =
          when (e) {
            is DataIntegrityViolationException -> "Add module failed. Dates are invalid."
            else -> "Add module failed: ${e.message}"
          }
    }

    return redirectToCohort(cohortId)
  }

  @PostMapping("/cohorts/{cohortId}/updateModule")
  fun updateModule(
      model: Model,
      @PathVariable cohortId: CohortId,
      @RequestParam moduleId: ModuleId,
      @RequestParam title: String,
      @RequestParam startDate: LocalDate,
      @RequestParam endDate: LocalDate,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { updateCohort(cohortId) }
    try {
      cohortStore.update(cohortId) {
        val newModule = CohortModuleModel(cohortId, moduleId, title, startDate, endDate)
        val updatedModules =
            it.modules.map { existingModule ->
              if (existingModule.moduleId == moduleId) {
                newModule
              } else {
                existingModule
              }
            }
        it.copy(modules = updatedModules)
      }
      redirectAttributes.successMessage = "Cohort module updated."
    } catch (e: Exception) {
      log.warn("Update cohort module failed")
      redirectAttributes.failureMessage =
          when (e) {
            is DataIntegrityViolationException -> "Update module failed. Dates are invalid."
            else -> "Update module failed: ${e.message}"
          }
    }

    return redirectToCohort(cohortId)
  }

  @PostMapping("/cohorts/{cohortId}/removeModule")
  fun removeModule(
      model: Model,
      @PathVariable cohortId: CohortId,
      @RequestParam moduleId: ModuleId,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { updateCohort(cohortId) }
    try {
      cohortStore.update(cohortId) {
        val updatedModules = it.modules.filter { cohortModule -> cohortModule.moduleId != moduleId }
        it.copy(modules = updatedModules)
      }
      redirectAttributes.successMessage = "Module removed from cohort."
    } catch (e: Exception) {
      log.warn("Delete module failed")
      redirectAttributes.failureMessage = "Remove module failed: ${e.message}"
    }

    return redirectToCohort(cohortId)
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
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { readAllDeliverables() }

    val cohort =
        try {
          cohortStore.fetchOneById(cohortId, CohortDepth.Cohort, CohortModuleDepth.Module)
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
          "Deliverable $deliverableId not assocaited with cohort $cohortId"
      return redirectToCohort(cohortId)
    }

    val module =
        try {
          moduleStore.fetchOneById(dueDateModel.moduleId)
        } catch (e: ModuleNotFoundException) {
          redirectAttributes.failureMessage = "Module for deliverable not found: ${e.message}"
          return redirectToCohort(cohortId)
        }

    val cohortProjects = module.cohorts.first { it.cohortId == cohortId }.projects!!

    val cohortModule = cohort.modules.first { it.moduleId == module.id }
    val deliverable = module.deliverables.first { it.id == deliverableId }

    val submissions =
        deliverableStore
            .fetchDeliverableSubmissions(deliverableId = deliverableId)
            .filter { cohortProjects.contains(it.projectId) }
            .sortedBy { it.projectId.value }

    model.addAttribute("canManageDeliverables", currentUser().canManageDeliverables())
    model.addAttribute("cohort", cohort)
    model.addAttribute("cohortModule", cohortModule)
    model.addAttribute("deliverable", deliverable)
    model.addAttribute("dueDates", dueDateModel)
    model.addAttribute("module", module)
    model.addAttribute("submissions", submissions)

    return "/admin/cohortDeliverable"
  }

  @PostMapping("/cohorts/{cohortId}/deliverables/{deliverableId}")
  fun updateDeliverableDueDate(
      model: Model,
      @PathVariable cohortId: CohortId,
      @PathVariable deliverableId: DeliverableId,
      @RequestParam operation: String,
      @RequestParam projectId: ProjectId?,
      @RequestParam dueDate: LocalDate?,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { manageDeliverables() }
    when (operation) {
      "upsert" -> {
        if (dueDate == null) {
          redirectAttributes.failureMessage = "Due date must be set for upsert"
          return redirectToCohortDeliverable(cohortId, deliverableId)
        }

        try {
          if (projectId != null) {
            deliverableDueDateStore.upsertDeliverableProjectDueDate(
                deliverableId, projectId, dueDate)
          } else {
            deliverableDueDateStore.upsertDeliverableCohortDueDate(deliverableId, cohortId, dueDate)
          }
        } catch (e: Exception) {
          log.warn("Upsert deliverable due date failed", e)
          redirectAttributes.failureMessage = "Error updating deliverable due date: ${e.message}"
        }

        redirectAttributes.successMessage = "Successfully updated due date."
      }
      "remove" -> {
        try {
          if (projectId != null) {
            deliverableDueDateStore.deleteDeliverableProjectDueDate(deliverableId, projectId)
          } else {
            deliverableDueDateStore.deleteDeliverableCohortDueDate(deliverableId, cohortId)
          }
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
