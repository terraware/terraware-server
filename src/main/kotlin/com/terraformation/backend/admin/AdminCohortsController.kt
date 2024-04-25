package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.CohortNotFoundException
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.accelerator.model.CohortModuleDepth
import com.terraformation.backend.accelerator.model.CohortModuleModel
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.GlobalRole
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
    private val cohortStore: CohortStore,
    private val moduleStore: ModuleStore,
) {
  private val log = perClassLogger()

  @GetMapping("/cohorts")
  fun cohortsHome(model: Model): String {
    val cohorts = cohortStore.findAll()

    val canReadCohorts = cohorts.all { currentUser().canReadCohort(it.id) }

    model.addAttribute("cohorts", cohorts)
    model.addAttribute("canReadCohorts", canReadCohorts)

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

    val modules = moduleStore.fetchAllModules()
    val moduleNames = modules.associate { it.id to "(${it.id}) ${it.name}" }

    model.addAttribute("cohort", cohort)
    model.addAttribute("cohortModules", cohortModules)
    model.addAttribute("moduleNames", moduleNames)
    model.addAttribute("canUpdateCohort", currentUser().canUpdateCohort(cohortId))

    return "/admin/cohortView"
  }

  @PostMapping("/cohorts/{cohortId}/addModule")
  fun addModule(
      model: Model,
      @PathVariable cohortId: CohortId,
      @RequestParam moduleId: ModuleId,
      @RequestParam startDate: LocalDate,
      @RequestParam endDate: LocalDate,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { updateCohort(cohortId) }
    try {
      cohortStore.update(cohortId) {
        val updatedModules =
            it.modules.plus(CohortModuleModel(cohortId, moduleId, startDate, endDate))
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
      @RequestParam startDate: LocalDate,
      @RequestParam endDate: LocalDate,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { updateCohort(cohortId) }
    try {
      cohortStore.update(cohortId) {
        val newModule = CohortModuleModel(cohortId, moduleId, startDate, endDate)
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

  private fun redirectToCohort(cohortId: CohortId) = "redirect:/admin/cohorts/$cohortId"

  private fun redirectToCohortHome() = "redirect:/admin/cohorts"
}
