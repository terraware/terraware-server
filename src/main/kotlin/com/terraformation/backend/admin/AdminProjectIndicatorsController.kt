package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.ReportIndicatorStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATORS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PROJECT_INDICATORS
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
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminProjectIndicatorsController(
    val dslContext: DSLContext,
    val reportIndicatorStore: ReportIndicatorStore,
    val projectStore: ProjectStore,
) {
  @GetMapping("/projectIndicators")
  fun projectIndicatorsProjectList(model: Model): String {
    requirePermissions { manageProjectReportConfigs() }
    val projectsWithIndicators =
        dslContext
            .selectDistinct(PROJECTS.ID, PROJECTS.NAME)
            .from(PROJECT_INDICATORS)
            .join(PROJECTS)
            .on(PROJECTS.ID.eq(PROJECT_INDICATORS.PROJECT_ID))
            .fetchMap(PROJECTS.ID.asNonNullable(), PROJECTS.NAME.asNonNullable())

    model.addAttribute("projectsWithIndicators", projectsWithIndicators)

    return "/admin/projectsWithIndicators"
  }

  @GetMapping("/projectIndicators/{projectId}")
  fun projectIndicatorsSingleProjectView(model: Model, @PathVariable projectId: ProjectId): String {
    requirePermissions { manageProjectReportConfigs() }

    val project = projectStore.fetchOneById(projectId)
    val projectIndicators = reportIndicatorStore.fetchProjectIndicatorsForProject(projectId)

    model.addAttribute("projectIndicators", projectIndicators)
    model.addAttribute("project", project)

    return "/admin/projectIndicators"
  }

  @PostMapping("/deleteProjectIndicators")
  fun deleteProjectIndicators(
      @RequestParam indicatorId: ProjectIndicatorId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { manageProjectReportConfigs() }

    try {
      dslContext.transaction { _ ->
        dslContext
            .deleteFrom(PUBLISHED_REPORT_PROJECT_INDICATORS)
            .where(PUBLISHED_REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID.eq(indicatorId))
            .execute()

        dslContext
            .deleteFrom(REPORT_PROJECT_INDICATORS)
            .where(REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID.eq(indicatorId))
            .execute()

        dslContext
            .deleteFrom(PROJECT_INDICATORS)
            .where(PROJECT_INDICATORS.ID.eq(indicatorId))
            .execute()
      }
      redirectAttributes.successMessage = "Successfully deleted project indicators"
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Failed to delete project indicators: ${e.message}"
    }

    return "redirect:/admin/projectIndicators"
  }
}
