package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import org.jooq.DSLContext
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
    private val dslContext: DSLContext,
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

    return redirectToCohort(CohortId(cohortId))
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

    return redirectToCohort(CohortId(cohortId))
  }

  @PostMapping("/cohorts/{cohortId}/removeModule")
  fun removeModule(
      model: Model,
      @PathVariable cohortId: String,
      @RequestParam moduleId: ModuleId,
      redirectAttributes: RedirectAttributes
  ): String {

    return redirectToCohort(CohortId(cohortId))
  }

  private fun redirectToCohort(cohortId: CohortId) = "redirect:/admin/cohorts/$cohortId"

  private fun redirectToCohortsHome() = "redirect:/admin/cohorts"
}
