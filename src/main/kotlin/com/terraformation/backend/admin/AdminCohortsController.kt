package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.CohortNotFoundException
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.accelerator.model.CohortModuleDepth
import com.terraformation.backend.accelerator.model.CohortModuleModel
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.GlobalRole
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
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminCohortsController(
    private val cohortStore: CohortStore,
    private val moduleStore: ModuleStore,
) {
  private val log = perClassLogger()

  @GetMapping("/cohorts")
  fun cohortsHome(model: Model): String {
    val cohorts = cohortStore.findAll()

    val canUpdateCohorts = cohorts.all { currentUser().canUpdateCohort(it.id) }

    model.addAttribute("cohorts", cohorts)
    model.addAttribute("canUpdateCohorts", canUpdateCohorts)

    return "/admin/cohorts"
  }

  @GetMapping("/cohorts/{cohortId}")
  fun cohortView(
      model: Model,
      @PathVariable cohortId: String,
      redirectAttributes: RedirectAttributes
  ): String {
    val id = CohortId(cohortId)
    val cohort =
        try {
          cohortStore.fetchOneById(id, CohortDepth.Cohort, CohortModuleDepth.Module)
        } catch (e: CohortNotFoundException) {
          log.warn("Cohort not found", e)
          redirectAttributes.failureMessage = "Cohort not found: ${e.message}"
          return redirectToCohortHome()
        }
    val cohortModules = cohort.modules.map { it.moduleId }

    val modules = moduleStore.fetchAllModules()
    val moduleNames = modules.associate { it.id to "(" + it.id.toString() + ") " + it.name }

    model.addAttribute("cohort", cohort)
    model.addAttribute("cohortModules", cohortModules)
    model.addAttribute("moduleNames", moduleNames)
    model.addAttribute("canUpdateCohort", currentUser().canUpdateCohort(id))

    return "/admin/cohortView"
  }

  @PostMapping("/cohorts/{cohortId}/addModule")
  fun addModule(
      model: Model,
      @PathVariable cohortId: String,
      @RequestParam moduleId: ModuleId,
      @RequestParam startDate: String,
      @RequestParam endDate: String,
      redirectAttributes: RedirectAttributes
  ): String {
    val id = CohortId(cohortId)
    try {
      cohortStore.update(id) {
        it.copy(
            modules =
                it.modules.plus(
                    CohortModuleModel(
                        id,
                        moduleId,
                        LocalDate.parse(startDate),
                        LocalDate.parse(endDate),
                    )))
      }
      redirectAttributes.successMessage = "Cohort module added."
    } catch (e: Exception) {
      log.warn("Update module failed")
      redirectAttributes.failureMessage = "Add module failed: ${e.message}"
    }

    return redirectToCohort(id)
  }

  @PostMapping("/cohorts/{cohortId}/updateModule")
  fun updateModule(
      model: Model,
      @PathVariable cohortId: String,
      @RequestParam moduleId: ModuleId,
      @RequestParam startDate: String,
      @RequestParam endDate: String,
      redirectAttributes: RedirectAttributes
  ): String {
    val id = CohortId(cohortId)
    try {
      cohortStore.update(id) {
        it.copy(
            modules =
                it.modules.map { cohortModule ->
                  if (cohortModule.moduleId == moduleId) {
                    cohortModule.copy(
                        startDate = LocalDate.parse(startDate), endDate = LocalDate.parse(endDate))
                  } else {
                    cohortModule
                  }
                })
      }
      redirectAttributes.successMessage = "Cohort module updated."
    } catch (e: Exception) {
      log.warn("Update module failed")
      redirectAttributes.failureMessage = "Update module failed: ${e.message}"
    }

    return redirectToCohort(id)
  }

  @PostMapping("/cohorts/{cohortId}/removeModule")
  fun removeModule(
      model: Model,
      @PathVariable cohortId: String,
      @RequestParam moduleId: ModuleId,
      redirectAttributes: RedirectAttributes
  ): String {
    val id = CohortId(cohortId)
    try {
      cohortStore.update(id) {
        it.copy(modules = it.modules.filter { cohortModule -> cohortModule.moduleId != moduleId })
      }
      redirectAttributes.successMessage = "Module removed from cohort."
    } catch (e: Exception) {
      log.warn("Delete module failed")
      redirectAttributes.failureMessage = "Remove module failed: ${e.message}"
    }

    return redirectToCohort(id)
  }

  private fun redirectToCohort(cohortId: CohortId) = "redirect:/admin/cohorts/$cohortId"

  private fun redirectToCohortHome() = "redirect:/admin/cohorts"
}
