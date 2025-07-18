package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ReportMetricStore
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportMetricStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/projects/{projectId}/reports")
@RestController
class ProjectReportsController(
    private val metricStore: ReportMetricStore,
    private val reportStore: ReportStore
) {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "List project accelerator reports.",
      description =
          "By default, reports more than 30 days in the future, or marked as Not Needed will be " +
              "omitted. Optionally query by year, or include metrics.")
  fun listAcceleratorReports(
      @PathVariable projectId: ProjectId,
      @RequestParam year: Int? = null,
      @RequestParam includeArchived: Boolean? = null,
      @RequestParam includeFuture: Boolean? = null,
      @RequestParam includeMetrics: Boolean? = null,
  ): ListAcceleratorReportsResponsePayload {
    val reports =
        reportStore.fetch(
            projectId = projectId,
            year = year,
            includeArchived = includeArchived ?: false,
            includeFuture = includeFuture ?: false,
            includeMetrics = includeMetrics ?: false,
        )
    return ListAcceleratorReportsResponsePayload(reports.map { AcceleratorReportPayload(it) })
  }

  @ApiResponse200
  @GetMapping("/{reportId}")
  @Operation(summary = "Get one report.")
  fun getAcceleratorReport(
      @PathVariable reportId: ReportId,
      @RequestParam includeMetrics: Boolean? = null,
  ): GetAcceleratorReportResponsePayload {
    val model =
        reportStore.fetchOne(
            reportId = reportId,
            includeMetrics = includeMetrics ?: false,
        )
    return GetAcceleratorReportResponsePayload(AcceleratorReportPayload(model))
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}")
  @Operation(summary = "Update metric data and qualitative data for a report")
  fun updateAcceleratorReportValues(
      @PathVariable reportId: ReportId,
      @RequestBody payload: UpdateAcceleratorReportValuesRequestPayload,
  ): SimpleSuccessResponsePayload {
    val standardMetricUpdates = payload.standardMetrics.associate { it.id to it.toModel() }
    val systemMetricUpdates = payload.systemMetrics.associate { it.metric to it.toModel() }
    val projectMetricUpdates = payload.projectMetrics.associate { it.id to it.toModel() }

    reportStore.updateReport(
        reportId = reportId,
        highlights = payload.highlights,
        achievements = payload.achievements,
        challenges = payload.challenges.map { it.toModel() },
        standardMetricEntries = standardMetricUpdates,
        systemMetricEntries = systemMetricUpdates,
        projectMetricEntries = projectMetricUpdates,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/metrics/refresh")
  @Operation(summary = "Refresh system metric entries value for a report")
  fun refreshAcceleratorReportSystemMetrics(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestParam metrics: List<SystemMetric>,
  ): SimpleSuccessResponsePayload {
    reportStore.refreshSystemMetricValues(reportId, metrics)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/review")
  @Operation(summary = "Review a report")
  fun reviewAcceleratorReport(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestBody payload: ReviewAcceleratorReportRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.reviewReport(
        reportId = reportId,
        status = payload.review.status,
        highlights = payload.review.highlights,
        achievements = payload.review.achievements,
        challenges = payload.review.challenges.map { it.toModel() },
        feedback = payload.review.feedback,
        internalComment = payload.review.internalComment,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/metrics/review")
  @Operation(summary = "Review metric entries for a report")
  fun reviewAcceleratorReportMetrics(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestBody payload: ReviewAcceleratorReportMetricsRequestPayload,
  ): SimpleSuccessResponsePayload {
    val standardMetricUpdates = payload.standardMetrics.associate { it.id to it.toModel() }
    val systemMetricUpdates = payload.systemMetrics.associate { it.metric to it.toModel() }
    val projectMetricUpdates = payload.projectMetrics.associate { it.id to it.toModel() }

    reportStore.reviewReportMetrics(
        reportId = reportId,
        standardMetricEntries = standardMetricUpdates,
        systemMetricEntries = systemMetricUpdates,
        projectMetricEntries = projectMetricUpdates,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/submit")
  @Operation(summary = "Submits a report for review")
  fun submitAcceleratorReport(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
  ): SimpleSuccessResponsePayload {
    reportStore.submitReport(reportId)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/publish")
  @Operation(summary = "Publishes a report to funder")
  fun publishAcceleratorReport(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
  ): SimpleSuccessResponsePayload {
    reportStore.publishReport(reportId)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PutMapping("/configs")
  @Operation(
      summary = "Insert accelerator report configuration.",
      description =
          "Set up an accelerator report configuration for a project. This will create" +
              "all the reports within the reporting period.")
  fun createAcceleratorReportConfig(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: CreateAcceleratorReportConfigRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.insertProjectReportConfig(
        payload.config.toModel(projectId, ReportFrequency.Quarterly))
    reportStore.insertProjectReportConfig(payload.config.toModel(projectId, ReportFrequency.Annual))

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @GetMapping("/configs")
  @Operation(summary = "List accelerator report configurations.")
  fun listAcceleratorReportConfig(
      @PathVariable projectId: ProjectId,
  ): ListAcceleratorReportConfigResponsePayload {
    val configs = reportStore.fetchProjectReportConfigs(projectId)
    return ListAcceleratorReportConfigResponsePayload(
        configs.map { ExistingAcceleratorReportConfigPayload(it) })
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/configs")
  @Operation(summary = "Update all accelerator report configurations for a project.")
  fun updateProjectAcceleratorReportConfig(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateProjectAcceleratorReportConfigRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateProjectReportConfig(
        projectId,
        payload.config.reportingStartDate,
        payload.config.reportingEndDate,
        payload.config.logframeUrl)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/configs/{configId}")
  @Operation(summary = "Update accelerator report configuration.")
  fun updateAcceleratorReportConfig(
      @PathVariable configId: ProjectReportConfigId,
      @RequestBody payload: UpdateAcceleratorReportConfigRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateProjectReportConfig(
        configId,
        payload.config.reportingStartDate,
        payload.config.reportingEndDate,
        payload.config.logframeUrl)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @GetMapping("/metrics")
  @Operation(summary = "List all project metrics for one project.")
  fun listProjectMetrics(@PathVariable projectId: ProjectId): ListProjectMetricsResponsePayload {
    val models = metricStore.fetchProjectMetricsForProject(projectId)
    return ListProjectMetricsResponsePayload(models.map { ExistingProjectMetricPayload(it) })
  }

  @ApiResponse200
  @PutMapping("/metrics")
  @Operation(summary = "Insert project metric, that the project will report on all future reports.")
  fun createProjectMetric(
      @PathVariable projectId: ProjectId,
      @RequestBody @Valid payload: CreateProjectMetricRequestPayload,
  ): SimpleSuccessResponsePayload {
    metricStore.createProjectMetric(payload.metric.toProjectMetricModel(projectId))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/metrics/{metricId}")
  @Operation(summary = "Update one project metric by ID.")
  fun updateProjectMetric(
      @PathVariable metricId: ProjectMetricId,
      @PathVariable projectId: ProjectId,
      @RequestBody @Valid payload: UpdateProjectMetricRequestPayload,
  ): SimpleSuccessResponsePayload {
    metricStore.updateProjectMetric(metricId) { payload.metric.toModel() }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @PostMapping("/targets")
  @Operation(summary = "Update project metric targets.")
  fun updateProjectMetricTargets(
      @PathVariable projectId: ProjectId,
      @RequestParam
      @Schema(description = "Update targets for submitted reports. Require TF Experts privileges.")
      updateSubmitted: Boolean?,
      @RequestBody payload: UpdateMetricTargetsRequestPayload,
  ): SimpleSuccessResponsePayload {
    payload.metric.updateMetricTargets(reportStore, projectId, updateSubmitted ?: false)
    return SimpleSuccessResponsePayload()
  }
}

data class ExistingAcceleratorReportConfigPayload(
    val configId: ProjectReportConfigId,
    val projectId: ProjectId,
    val frequency: ReportFrequency,
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
    val logframeUrl: URI?,
) {
  constructor(
      model: ExistingProjectReportConfigModel
  ) : this(
      configId = model.id,
      projectId = model.projectId,
      frequency = model.frequency,
      reportingStartDate = model.reportingStartDate,
      reportingEndDate = model.reportingEndDate,
      logframeUrl = model.logframeUrl,
  )
}

data class UpdateAcceleratorReportConfigPayload(
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
    val logframeUrl: URI?,
)

data class NewAcceleratorReportConfigPayload(
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
    val logframeUrl: URI?,
) {
  fun toModel(projectId: ProjectId, frequency: ReportFrequency): NewProjectReportConfigModel =
      NewProjectReportConfigModel(
          id = null,
          projectId = projectId,
          frequency = frequency,
          reportingStartDate = reportingStartDate,
          reportingEndDate = reportingEndDate,
          logframeUrl = logframeUrl,
      )
}

data class AcceleratorReportPayload(
    val id: ReportId,
    val projectId: ProjectId,
    val frequency: ReportFrequency,
    val quarter: ReportQuarter?,
    val status: ReportStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val highlights: String?,
    val achievements: List<String>,
    val challenges: List<ReportChallengePayload>,
    val internalComment: String?,
    val feedback: String?,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val submittedBy: UserId?,
    val submittedTime: Instant?,
    val projectMetrics: List<ReportProjectMetricPayload>,
    val standardMetrics: List<ReportStandardMetricPayload>,
    val systemMetrics: List<ReportSystemMetricPayload>,
) {
  constructor(
      model: ReportModel
  ) : this(
      id = model.id,
      projectId = model.projectId,
      frequency = model.frequency,
      quarter = model.quarter,
      status = model.status,
      startDate = model.startDate,
      endDate = model.endDate,
      highlights = model.highlights,
      achievements = model.achievements,
      challenges = model.challenges.map { ReportChallengePayload(it) },
      internalComment = model.internalComment,
      feedback = model.feedback,
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      submittedBy = model.submittedBy,
      submittedTime = model.submittedTime,
      projectMetrics = model.projectMetrics.map { ReportProjectMetricPayload(it) },
      standardMetrics = model.standardMetrics.map { ReportStandardMetricPayload(it) },
      systemMetrics = model.systemMetrics.map { ReportSystemMetricPayload(it) },
  )
}

data class ReportChallengePayload(
    val challenge: String,
    val mitigationPlan: String,
) {
  constructor(model: ReportChallengeModel) : this(model.challenge, model.mitigationPlan)

  fun toModel() = ReportChallengeModel(challenge = challenge, mitigationPlan = mitigationPlan)
}

data class ReportReviewPayload(
    @Schema(description = "Must be unchanged if a report has not been submitted yet.")
    val status: ReportStatus,
    val highlights: String?,
    val achievements: List<String>,
    val challenges: List<ReportChallengePayload>,
    val feedback: String?,
    val internalComment: String?,
)

data class ReportStandardMetricPayload(
    val id: StandardMetricId,
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: String,
    val isPublishable: Boolean,
    val target: Int?,
    val value: Int?,
    val status: ReportMetricStatus?,
    val underperformanceJustification: String?,
    val progressNotes: String?
) {
  constructor(
      model: ReportStandardMetricModel
  ) : this(
      id = model.metric.id,
      name = model.metric.name,
      description = model.metric.description,
      component = model.metric.component,
      type = model.metric.type,
      reference = model.metric.reference,
      isPublishable = model.metric.isPublishable,
      target = model.entry.target,
      value = model.entry.value,
      status = model.entry.status,
      underperformanceJustification = model.entry.underperformanceJustification,
      progressNotes = model.entry.progressNotes)
}

data class ReportStandardMetricEntriesPayload(
    val id: StandardMetricId,
    val target: Int?,
    val value: Int?,
    val status: ReportMetricStatus?,
    val underperformanceJustification: String?,
    val progressNotes: String?,
) {
  fun toModel() =
      ReportMetricEntryModel(
          target = target,
          value = value,
          underperformanceJustification = underperformanceJustification,
          progressNotes = progressNotes,
          status = status,
      )
}

data class ReportSystemMetricPayload(
    val metric: SystemMetric,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: String,
    val isPublishable: Boolean,
    val target: Int?,
    val systemValue: Int,
    val systemTime: Instant?,
    val overrideValue: Int?,
    val status: ReportMetricStatus?,
    val underperformanceJustification: String?,
    val progressNotes: String?
) {
  constructor(
      model: ReportSystemMetricModel
  ) : this(
      metric = model.metric,
      description = model.metric.description,
      component = model.metric.componentId,
      type = model.metric.typeId,
      reference = model.metric.reference,
      isPublishable = model.metric.isPublishable,
      target = model.entry.target,
      systemValue = model.entry.systemValue,
      systemTime = model.entry.systemTime,
      overrideValue = model.entry.overrideValue,
      status = model.entry.status,
      underperformanceJustification = model.entry.underperformanceJustification,
      progressNotes = model.entry.progressNotes)
}

data class ReportSystemMetricEntriesPayload(
    val metric: SystemMetric,
    val target: Int?,
    @Schema(description = "If set to null, system metric entry will use Terraware data value.")
    val overrideValue: Int?,
    val status: ReportMetricStatus?,
    val underperformanceJustification: String?,
    val progressNotes: String?,
) {
  fun toModel() =
      ReportMetricEntryModel(
          target = target,
          value = overrideValue,
          underperformanceJustification = underperformanceJustification,
          progressNotes = progressNotes,
          status = status,
      )
}

data class ReportProjectMetricPayload(
    val id: ProjectMetricId,
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: String,
    val isPublishable: Boolean,
    val target: Int?,
    val value: Int?,
    val status: ReportMetricStatus?,
    val underperformanceJustification: String?,
    val progressNotes: String?,
    val unit: String?,
) {
  constructor(
      model: ReportProjectMetricModel
  ) : this(
      id = model.metric.id,
      name = model.metric.name,
      description = model.metric.description,
      component = model.metric.component,
      type = model.metric.type,
      reference = model.metric.reference,
      isPublishable = model.metric.isPublishable,
      target = model.entry.target,
      value = model.entry.value,
      status = model.entry.status,
      underperformanceJustification = model.entry.underperformanceJustification,
      progressNotes = model.entry.progressNotes,
      unit = model.metric.unit,
  )
}

data class ReportProjectMetricEntriesPayload(
    val id: ProjectMetricId,
    val target: Int?,
    val value: Int?,
    val underperformanceJustification: String?,
    val progressNotes: String?,
    val status: ReportMetricStatus?,
) {
  fun toModel() =
      ReportMetricEntryModel(
          target = target,
          value = value,
          underperformanceJustification = underperformanceJustification,
          progressNotes = progressNotes,
          status = status,
      )
}

data class CreateAcceleratorReportConfigRequestPayload(
    val config: NewAcceleratorReportConfigPayload
)

data class UpdateAcceleratorReportConfigRequestPayload(
    val config: UpdateAcceleratorReportConfigPayload
)

data class UpdateProjectAcceleratorReportConfigRequestPayload(
    val config: UpdateAcceleratorReportConfigPayload,
)

data class ReviewAcceleratorReportRequestPayload(
    val review: ReportReviewPayload,
)

data class ReviewAcceleratorReportMetricsRequestPayload(
    val standardMetrics: List<ReportStandardMetricEntriesPayload>,
    val systemMetrics: List<ReportSystemMetricEntriesPayload>,
    val projectMetrics: List<ReportProjectMetricEntriesPayload>,
)

data class UpdateAcceleratorReportValuesRequestPayload(
    val highlights: String?,
    val achievements: List<String>,
    val challenges: List<ReportChallengePayload>,
    val standardMetrics: List<ReportStandardMetricEntriesPayload>,
    val systemMetrics: List<ReportSystemMetricEntriesPayload>,
    val projectMetrics: List<ReportProjectMetricEntriesPayload>,
)

data class ListAcceleratorReportsResponsePayload(val reports: List<AcceleratorReportPayload>) :
    SuccessResponsePayload

data class GetAcceleratorReportResponsePayload(val report: AcceleratorReportPayload) :
    SuccessResponsePayload

data class ListAcceleratorReportConfigResponsePayload(
    val configs: List<ExistingAcceleratorReportConfigPayload>
) : SuccessResponsePayload

data class ListProjectMetricsResponsePayload(val metrics: List<ExistingProjectMetricPayload>) :
    SuccessResponsePayload

data class CreateProjectMetricRequestPayload(@field:Valid val metric: NewMetricPayload)

data class UpdateProjectMetricRequestPayload(@field:Valid val metric: ExistingProjectMetricPayload)

data class UpdateMetricTargetsRequestPayload(val metric: UpdateMetricTargetsPayload)
