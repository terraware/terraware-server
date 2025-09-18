package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ReportService
import com.terraformation.backend.accelerator.db.ReportMetricStore
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.accelerator.model.ReportProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
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
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/projects/{projectId}/reports")
@RestController
class ProjectReportsController(
    private val metricStore: ReportMetricStore,
    private val reportService: ReportService,
    private val reportStore: ReportStore,
) {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "List project accelerator reports.",
      description =
          "By default, reports more than 30 days in the future, or marked as Not Needed will be " +
              "omitted. Optionally query by year, or include metrics.",
  )
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
        financialSummaries = payload.financialSummaries,
        additionalComments = payload.additionalComments,
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
        financialSummaries = payload.review.financialSummaries,
        additionalComments = payload.review.additionalComments,
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
    reportService.publishReport(reportId)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Deletes a report photo")
  @DeleteMapping("/{reportId}/photos/{fileId}")
  @RequestBodyPhotoFile
  fun deleteAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @PathVariable fileId: FileId,
  ): SimpleSuccessResponsePayload {
    reportService.deleteReportPhoto(
        reportId = reportId,
        fileId = fileId,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200Photo
  @ApiResponse404("The report does not exist, or does not have a photo with the requested ID.")
  @GetMapping(
      "/{reportId}/photos/{fileId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE],
  )
  @Operation(
      summary = "Retrieves a specific photo from a report",
      description = PHOTO_OPERATION_DESCRIPTION,
  )
  @ResponseBody
  fun getAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @PathVariable fileId: FileId,
      @Parameter(description = PHOTO_MAXWIDTH_DESCRIPTION) @RequestParam maxWidth: Int? = null,
      @Parameter(description = PHOTO_MAXHEIGHT_DESCRIPTION) @RequestParam maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return reportService.readReportPhoto(reportId, fileId, maxWidth, maxHeight).toResponseEntity()
  }

  @Operation(summary = "Updates a report photo caption")
  @PutMapping("/{reportId}/photos/{fileId}")
  @RequestBodyPhotoFile
  fun updateAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @PathVariable fileId: FileId,
      @RequestBody payload: UpdateAcceleratorReportPhotoRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportService.updateReportPhotoCaption(
        caption = payload.caption,
        reportId = reportId,
        fileId = fileId,
    )

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Uploads a photo to a report.")
  @PostMapping(
      "/{reportId}/photos",
      consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
  )
  @RequestBodyPhotoFile
  fun uploadAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @RequestPart file: MultipartFile,
      @RequestPart(required = false) caption: String?,
  ): UploadAcceleratorReportPhotoResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val filename = file.getFilename("photo")

    val fileId =
        reportService.storeReportPhoto(
            caption = caption,
            data = file.inputStream,
            metadata = FileMetadata.of(contentType, filename, file.size),
            reportId = reportId,
        )

    return UploadAcceleratorReportPhotoResponsePayload(fileId)
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PutMapping("/configs")
  @Operation(
      summary = "Insert accelerator report configuration.",
      description =
          "Set up an accelerator report configuration for a project. This will create" +
              "all the reports within the reporting period.",
  )
  fun createAcceleratorReportConfig(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: CreateAcceleratorReportConfigRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.insertProjectReportConfig(
        payload.config.toModel(projectId, ReportFrequency.Quarterly)
    )
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
        configs.map { ExistingAcceleratorReportConfigPayload(it) }
    )
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
        payload.config.logframeUrl,
    )
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
        payload.config.logframeUrl,
    )
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
    val logframeUrl: URI?,
    val reportingEndDate: LocalDate,
    val reportingStartDate: LocalDate,
)

data class NewAcceleratorReportConfigPayload(
    val logframeUrl: URI?,
    val reportingEndDate: LocalDate,
    val reportingStartDate: LocalDate,
) {
  fun toModel(projectId: ProjectId, frequency: ReportFrequency): NewProjectReportConfigModel =
      NewProjectReportConfigModel(
          frequency = frequency,
          id = null,
          logframeUrl = logframeUrl,
          projectId = projectId,
          reportingEndDate = reportingEndDate,
          reportingStartDate = reportingStartDate,
      )
}

data class AcceleratorReportPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val challenges: List<ReportChallengePayload>,
    val endDate: LocalDate,
    val feedback: String?,
    val financialSummaries: String?,
    val frequency: ReportFrequency,
    val highlights: String?,
    val id: ReportId,
    val internalComment: String?,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val photos: List<ReportPhotoPayload>,
    val projectId: ProjectId,
    val projectMetrics: List<ReportProjectMetricPayload>,
    val quarter: ReportQuarter?,
    val standardMetrics: List<ReportStandardMetricPayload>,
    val startDate: LocalDate,
    val status: ReportStatus,
    val submittedBy: UserId?,
    val submittedTime: Instant?,
    val systemMetrics: List<ReportSystemMetricPayload>,
) {
  constructor(
      model: ReportModel
  ) : this(
      achievements = model.achievements,
      additionalComments = model.additionalComments,
      challenges = model.challenges.map { ReportChallengePayload(it) },
      endDate = model.endDate,
      feedback = model.feedback,
      financialSummaries = model.financialSummaries,
      frequency = model.frequency,
      highlights = model.highlights,
      id = model.id,
      internalComment = model.internalComment,
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      photos = model.photos.map { ReportPhotoPayload(it) },
      projectId = model.projectId,
      projectMetrics = model.projectMetrics.map { ReportProjectMetricPayload(it) },
      quarter = model.quarter,
      standardMetrics = model.standardMetrics.map { ReportStandardMetricPayload(it) },
      startDate = model.startDate,
      status = model.status,
      submittedBy = model.submittedBy,
      submittedTime = model.submittedTime,
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
    val financialSummaries: String?,
    val additionalComments: String?,
    val challenges: List<ReportChallengePayload>,
    val feedback: String?,
    val internalComment: String?,
)

data class ReportStandardMetricPayload(
    val component: MetricComponent,
    val description: String?,
    val id: StandardMetricId,
    val isPublishable: Boolean,
    val name: String,
    val progressNotes: String?,
    val projectsComments: String?,
    val reference: String,
    val status: ReportMetricStatus?,
    val target: Int?,
    val type: MetricType,
    val value: Int?,
) {
  constructor(
      model: ReportStandardMetricModel
  ) : this(
      component = model.metric.component,
      description = model.metric.description,
      id = model.metric.id,
      isPublishable = model.metric.isPublishable,
      name = model.metric.name,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      reference = model.metric.reference,
      status = model.entry.status,
      target = model.entry.target,
      type = model.metric.type,
      value = model.entry.value,
  )
}

data class ReportStandardMetricEntriesPayload(
    val id: StandardMetricId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportMetricStatus?,
    val target: Int?,
    val value: Int?,
) {
  fun toModel() =
      ReportMetricEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          target = target,
          value = value,
      )
}

data class ReportSystemMetricPayload(
    val component: MetricComponent,
    val description: String?,
    val isPublishable: Boolean,
    val metric: SystemMetric,
    val overrideValue: Int?,
    val progressNotes: String?,
    val projectsComments: String?,
    val reference: String,
    val status: ReportMetricStatus?,
    val systemTime: Instant?,
    val systemValue: Int,
    val target: Int?,
    val type: MetricType,
) {
  constructor(
      model: ReportSystemMetricModel
  ) : this(
      component = model.metric.componentId,
      description = model.metric.description,
      isPublishable = model.metric.isPublishable,
      metric = model.metric,
      overrideValue = model.entry.overrideValue,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      reference = model.metric.reference,
      status = model.entry.status,
      systemTime = model.entry.systemTime,
      systemValue = model.entry.systemValue,
      target = model.entry.target,
      type = model.metric.typeId,
  )
}

data class ReportSystemMetricEntriesPayload(
    val metric: SystemMetric,
    val overrideValue: Int?,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportMetricStatus?,
    val target: Int?,
) {
  fun toModel() =
      ReportMetricEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          target = target,
          value = overrideValue,
      )
}

data class ReportPhotoPayload(
    val caption: String?,
    val fileId: FileId,
) {
  constructor(
      model: ReportPhotoModel
  ) : this(
      caption = model.caption,
      fileId = model.fileId,
  )
}

data class ReportProjectMetricPayload(
    val component: MetricComponent,
    val description: String?,
    val id: ProjectMetricId,
    val isPublishable: Boolean,
    val name: String,
    val progressNotes: String?,
    val projectsComments: String?,
    val reference: String,
    val status: ReportMetricStatus?,
    val target: Int?,
    val type: MetricType,
    val unit: String?,
    val value: Int?,
) {
  constructor(
      model: ReportProjectMetricModel
  ) : this(
      component = model.metric.component,
      description = model.metric.description,
      id = model.metric.id,
      isPublishable = model.metric.isPublishable,
      name = model.metric.name,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      reference = model.metric.reference,
      status = model.entry.status,
      target = model.entry.target,
      type = model.metric.type,
      unit = model.metric.unit,
      value = model.entry.value,
  )
}

data class ReportProjectMetricEntriesPayload(
    val id: ProjectMetricId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportMetricStatus?,
    val target: Int?,
    val value: Int?,
) {
  fun toModel() =
      ReportMetricEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          target = target,
          value = value,
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
    val projectMetrics: List<ReportProjectMetricEntriesPayload>,
    val standardMetrics: List<ReportStandardMetricEntriesPayload>,
    val systemMetrics: List<ReportSystemMetricEntriesPayload>,
)

data class UpdateAcceleratorReportValuesRequestPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val challenges: List<ReportChallengePayload>,
    val financialSummaries: String?,
    val highlights: String?,
    val projectMetrics: List<ReportProjectMetricEntriesPayload>,
    val standardMetrics: List<ReportStandardMetricEntriesPayload>,
    val systemMetrics: List<ReportSystemMetricEntriesPayload>,
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

data class UpdateAcceleratorReportPhotoRequestPayload(val caption: String?)

data class UploadAcceleratorReportPhotoResponsePayload(val fileId: FileId) : SuccessResponsePayload
