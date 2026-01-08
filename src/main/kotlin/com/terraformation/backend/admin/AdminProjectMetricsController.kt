package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.ReportMetricStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PROJECT_METRICS
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
class AdminProjectMetricsController(
    val dslContext: DSLContext,
    val reportMetricStore: ReportMetricStore,
    val projectStore: ProjectStore,
) {
  @GetMapping("/projectMetrics")
  fun projectMetricsProjectList(model: Model): String {
    requirePermissions { manageProjectReportConfigs() }
    val projectsWithMetrics =
        dslContext
            .selectDistinct(PROJECTS.ID, PROJECTS.NAME)
            .from(PROJECT_METRICS)
            .join(PROJECTS)
            .on(PROJECTS.ID.eq(PROJECT_METRICS.PROJECT_ID))
            .fetchMap(PROJECTS.ID.asNonNullable(), PROJECTS.NAME.asNonNullable())

    model.addAttribute("projectsWithMetrics", projectsWithMetrics)

    return "/admin/projectsWithMetrics"
  }

  @GetMapping("/projectMetrics/{projectId}")
  fun projectMetricsSingleProjectView(model: Model, @PathVariable projectId: ProjectId): String {
    requirePermissions { manageProjectReportConfigs() }

    val project = projectStore.fetchOneById(projectId)
    val projectMetrics = reportMetricStore.fetchProjectMetricsForProject(projectId)

    model.addAttribute("projectMetrics", projectMetrics)
    model.addAttribute("project", project)

    return "/admin/projectMetrics"
  }

  @PostMapping("/deleteProjectMetrics")
  fun deleteProjectMetrics(
      @RequestParam metricId: ProjectMetricId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { manageProjectReportConfigs() }

    try {
      dslContext.transaction { _ ->
        dslContext
            .deleteFrom(PUBLISHED_REPORT_PROJECT_METRICS)
            .where(PUBLISHED_REPORT_PROJECT_METRICS.PROJECT_METRIC_ID.eq(metricId))
            .execute()

        dslContext
            .deleteFrom(REPORT_PROJECT_METRICS)
            .where(REPORT_PROJECT_METRICS.PROJECT_METRIC_ID.eq(metricId))
            .execute()

        dslContext.deleteFrom(PROJECT_METRICS).where(PROJECT_METRICS.ID.eq(metricId)).execute()
      }
      redirectAttributes.successMessage = "Successfully deleted project metrics"
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Failed to delete project metrics: ${e.message}"
    }

    return "redirect:/admin/projectMetrics"
  }
}
