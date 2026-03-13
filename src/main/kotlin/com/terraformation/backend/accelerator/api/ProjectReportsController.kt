package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ReportService
import com.terraformation.backend.accelerator.db.ReportIndicatorStore
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.model.AutoCalculatedIndicatorTargetsModel
import com.terraformation.backend.accelerator.model.CommonIndicatorTargetsModel
import com.terraformation.backend.accelerator.model.CumulativeIndicatorProgressModel
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectIndicatorTargetsModel
import com.terraformation.backend.accelerator.model.PublishedReportComparedProps
import com.terraformation.backend.accelerator.model.ReportAutoCalculatedIndicatorModel
import com.terraformation.backend.accelerator.model.ReportAutoCalculatedIndicatorTargetModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ReportCommonIndicatorTargetModel
import com.terraformation.backend.accelerator.model.ReportIndicatorEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.accelerator.model.ReportProjectIndicatorModel
import com.terraformation.backend.accelerator.model.ReportProjectIndicatorTargetModel
import com.terraformation.backend.accelerator.model.YearlyIndicatorTargetModel
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
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import java.math.BigDecimal
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
    private val indicatorStore: ReportIndicatorStore,
    private val reportService: ReportService,
    private val reportStore: ReportStore,
) {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "List project accelerator reports.",
      description =
          "By default, reports more than 30 days in the future, or marked as Not Needed will be " +
              "omitted. Optionally query by year, or include indicators.",
  )
  fun listAcceleratorReports(
      @PathVariable projectId: ProjectId,
      @RequestParam year: Int? = null,
      @RequestParam includeArchived: Boolean? = null,
      @RequestParam includeFuture: Boolean? = null,
      @Schema(description = "Use includeIndicators instead", deprecated = true)
      @RequestParam
      includeMetrics: Boolean? = null,
      @RequestParam includeIndicators: Boolean? = null,
  ): ListAcceleratorReportsResponsePayload {
    val reports =
        reportService.fetch(
            projectId = projectId,
            year = year,
            includeArchived = includeArchived ?: false,
            includeFuture = includeFuture ?: false,
            includeIndicators = includeIndicators ?: includeMetrics ?: false,
            computeUnpublishedChanges = false,
        )
    return ListAcceleratorReportsResponsePayload(reports.map { AcceleratorReportPayload(it) })
  }

  @ApiResponse200
  @GetMapping("/{reportId}")
  @Operation(summary = "Get one report.")
  fun getAcceleratorReport(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @Schema(description = "Use includeIndicators instead", deprecated = true)
      @RequestParam
      includeMetrics: Boolean? = null,
      @RequestParam includeIndicators: Boolean? = null,
  ): GetAcceleratorReportResponsePayload {
    val model =
        reportService.fetchOne(
            reportId = reportId,
            includeIndicators = includeIndicators ?: includeMetrics ?: false,
            computeUnpublishedChanges = true,
        )
    return GetAcceleratorReportResponsePayload(AcceleratorReportPayload(model))
  }

  @ApiResponse200
  @GetMapping("/years")
  @Operation(summary = "Get project reporting years")
  fun getAcceleratorReportYears(
      @PathVariable projectId: ProjectId,
  ): GetAcceleratorReportYearsResponsePayload {
    val model = reportStore.fetchProjectReportYears(projectId)
    val payload = model?.let { ReportYearsPayload(it.first, it.second) }

    return GetAcceleratorReportYearsResponsePayload(payload)
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}")
  @Operation(summary = "Update indicator data and qualitative data for a report")
  fun updateAcceleratorReportValues(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestBody payload: UpdateAcceleratorReportValuesRequestPayload,
  ): SimpleSuccessResponsePayload {
    val commonIndicatorUpdates =
        when {
          (payload.commonIndicators != null) ->
              payload.commonIndicators.associate { it.id to it.toModel() }
          (payload.standardMetrics != null) ->
              payload.standardMetrics.associate { it.id to it.toModel() }
          else -> {
            throw IllegalArgumentException(
                "Requires either commonIndicators or standardMetrics to be specified"
            )
          }
        }
    val autoCalculatedIndicatorUpdates =
        when {
          (payload.autoCalculatedIndicators != null) ->
              payload.autoCalculatedIndicators.associate { it.indicator to it.toModel() }
          (payload.systemMetrics != null) ->
              payload.systemMetrics.associate { it.metric to it.toModel() }
          else -> {
            throw IllegalArgumentException(
                "Requires either autoCalculatedIndicators or systemMetrics to be specified"
            )
          }
        }
    val projectIndicatorUpdates =
        when {
          (payload.projectIndicators != null) ->
              payload.projectIndicators.associate { it.id to it.toModel() }
          (payload.projectMetrics != null) ->
              payload.projectMetrics.associate { it.id to it.toModel() }
          else -> {
            throw IllegalArgumentException(
                "Requires either projectIndicators or projectMetrics to be specified"
            )
          }
        }

    reportStore.updateReport(
        reportId = reportId,
        highlights = payload.highlights,
        achievements = payload.achievements,
        challenges = payload.challenges.map { it.toModel() },
        financialSummaries = payload.financialSummaries,
        additionalComments = payload.additionalComments,
        commonIndicatorEntries = commonIndicatorUpdates,
        autoCalculatedIndicatorEntries = autoCalculatedIndicatorUpdates,
        projectIndicatorEntries = projectIndicatorUpdates,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/metrics/refresh")
  @Operation(summary = "Use /{reportId}/indicators/refresh instead", deprecated = true)
  fun refreshAcceleratorReportSystemMetrics(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestParam metrics: List<AutoCalculatedIndicator>,
  ): SimpleSuccessResponsePayload {
    reportStore.refreshAutoCalculatedIndicatorValues(reportId, metrics)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/indicators/refresh")
  @Operation(summary = "Refresh auto calculated indicator entries value for a report")
  fun refreshAcceleratorReportAutoCalculatedIndicators(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestParam indicators: List<AutoCalculatedIndicator>,
  ): SimpleSuccessResponsePayload {
    reportStore.refreshAutoCalculatedIndicatorValues(reportId, indicators)

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
  @Operation(summary = "Use /{reportId}/indicators/review instead", deprecated = true)
  fun reviewAcceleratorReportMetrics(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestBody payload: ReviewAcceleratorReportMetricsRequestPayload,
  ): SimpleSuccessResponsePayload {
    val commonIndicatorUpdates = payload.standardMetrics.associate { it.id to it.toModel() }
    val autoCalculatedIndicatorUpdates =
        payload.systemMetrics.associate { it.metric to it.toModel() }
    val projectIndicatorUpdates = payload.projectMetrics.associate { it.id to it.toModel() }

    reportStore.reviewReportIndicators(
        reportId = reportId,
        commonIndicatorEntries = commonIndicatorUpdates,
        autoCalculatedIndicatorEntries = autoCalculatedIndicatorUpdates,
        projectIndicatorEntries = projectIndicatorUpdates,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/indicators/review")
  @Operation(summary = "Review indicator entries for a report")
  fun reviewAcceleratorReportIndicators(
      @PathVariable projectId: ProjectId,
      @PathVariable reportId: ReportId,
      @RequestBody payload: ReviewAcceleratorReportIndicatorsRequestPayload,
  ): SimpleSuccessResponsePayload {
    val commonIndicatorUpdates = payload.commonIndicators.associate { it.id to it.toModel() }
    val autoCalculatedIndicatorUpdates =
        payload.autoCalculatedIndicators.associate { it.indicator to it.toModel() }
    val projectIndicatorUpdates = payload.projectIndicators.associate { it.id to it.toModel() }

    reportStore.reviewReportIndicators(
        reportId = reportId,
        commonIndicatorEntries = commonIndicatorUpdates,
        autoCalculatedIndicatorEntries = autoCalculatedIndicatorUpdates,
        projectIndicatorEntries = projectIndicatorUpdates,
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
      @PathVariable projectId: ProjectId,
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
      @PathVariable projectId: ProjectId,
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
      @PathVariable projectId: ProjectId,
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
      @PathVariable projectId: ProjectId,
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
    reportStore.insertProjectReportConfig(payload.config.toModel(projectId))

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
      @PathVariable projectId: ProjectId,
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
  @Operation(summary = "Use /indicators instead", deprecated = true)
  fun listProjectMetrics(@PathVariable projectId: ProjectId): ListProjectMetricsResponsePayload {
    val models = indicatorStore.fetchProjectIndicatorsForProject(projectId)
    return ListProjectMetricsResponsePayload(models.map { ExistingProjectMetricPayload(it) })
  }

  @ApiResponse200
  @PutMapping("/metrics")
  @Operation(summary = "Use /indicators instead", deprecated = true)
  fun createProjectMetric(
      @PathVariable projectId: ProjectId,
      @RequestBody @Valid payload: CreateProjectMetricRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.createProjectIndicator(payload.metric.toProjectIndicatorModel(projectId))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/metrics/{metricId}")
  @Operation(summary = "Use /indicators/{indicatorId} instead", deprecated = true)
  fun updateProjectMetric(
      @PathVariable metricId: ProjectIndicatorId,
      @PathVariable projectId: ProjectId,
      @RequestBody @Valid payload: UpdateProjectMetricRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.updateProjectIndicator(metricId) { payload.metric.toModel() }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @GetMapping("/indicators")
  @Operation(summary = "List all project indicators for one project.")
  fun listProjectIndicators(
      @PathVariable projectId: ProjectId
  ): ListProjectIndicatorsResponsePayload {
    val models = indicatorStore.fetchProjectIndicatorsForProject(projectId)
    return ListProjectIndicatorsResponsePayload(models.map { ExistingProjectIndicatorPayload(it) })
  }

  @ApiResponse200
  @PutMapping("/indicators")
  @Operation(
      summary = "Insert project indicator, that the project will report on all future reports."
  )
  fun createProjectIndicator(
      @PathVariable projectId: ProjectId,
      @RequestBody @Valid payload: CreateProjectIndicatorRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.createProjectIndicator(payload.indicator.toProjectIndicatorModel(projectId))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/indicators/{indicatorId}")
  @Operation(summary = "Update one project indicator by ID.")
  fun updateProjectIndicator(
      @PathVariable indicatorId: ProjectIndicatorId,
      @PathVariable projectId: ProjectId,
      @RequestBody @Valid payload: UpdateProjectIndicatorRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.updateProjectIndicator(indicatorId) { payload.indicator.toModel() }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/projectMetricTarget")
  @Operation(summary = "Update project metric target for a year.")
  fun updateProjectMetricTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateProjectMetricTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateProjectIndicatorTarget(
        projectId = projectId,
        year = payload.year,
        indicatorId = payload.metricId,
        target = payload.target?.toBigDecimal(),
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/standardMetricTarget")
  @Operation(summary = "Update standard metric target for a year.")
  fun updateStandardMetricTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateStandardMetricTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateCommonIndicatorTarget(
        projectId = projectId,
        year = payload.year,
        indicatorId = payload.metricId,
        target = payload.target?.toBigDecimal(),
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/systemMetricTarget")
  @Operation(summary = "Update system metric target for a year.")
  fun updateSystemMetricTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateSystemMetricTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateAutoCalculatedIndicatorTarget(
        projectId = projectId,
        year = payload.year,
        indicatorId = payload.metric,
        target = payload.target?.toBigDecimal(),
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @GetMapping("/projectMetricTargets")
  @Operation(summary = "Get all project metric targets for a project.")
  fun getProjectMetricTargets(
      @PathVariable projectId: ProjectId
  ): GetProjectMetricTargetsResponsePayload {
    val targets = reportStore.fetchReportProjectIndicatorTargets(projectId)
    return GetProjectMetricTargetsResponsePayload(
        targets.map { ReportProjectMetricTargetPayload(it) }
    )
  }

  @ApiResponse200
  @GetMapping("/standardMetricTargets")
  @Operation(summary = "Get all standard metric targets for a project.")
  fun getStandardMetricTargets(
      @PathVariable projectId: ProjectId
  ): GetStandardMetricTargetsResponsePayload {
    val targets = reportStore.fetchReportCommonIndicatorTargets(projectId)
    return GetStandardMetricTargetsResponsePayload(
        targets.map { ReportStandardMetricTargetPayload(it) }
    )
  }

  @ApiResponse200
  @GetMapping("/systemMetricTargets")
  @Operation(summary = "Get all system metric targets for a project.")
  fun getSystemMetricTargets(
      @PathVariable projectId: ProjectId
  ): GetSystemMetricTargetsResponsePayload {
    val targets = reportStore.fetchReportAutoCalculatedIndicatorTargets(projectId)
    return GetSystemMetricTargetsResponsePayload(
        targets.map { ReportSystemMetricTargetPayload(it) }
    )
  }

  @ApiResponse200
  @GetMapping("/projectIndicatorTargets")
  @Operation(summary = "Get all project indicator targets for a project.")
  fun getProjectIndicatorTargets(
      @PathVariable projectId: ProjectId
  ): GetProjectIndicatorTargetsResponsePayload {
    val targets = reportStore.fetchProjectIndicatorTargets(projectId)
    return GetProjectIndicatorTargetsResponsePayload(
        targets.map { ProjectIndicatorTargetsPayload(it) }
    )
  }

  @ApiResponse200
  @GetMapping("/commonIndicatorTargets")
  @Operation(summary = "Get all common indicator targets for a project.")
  fun getCommonIndicatorTargets(
      @PathVariable projectId: ProjectId
  ): GetCommonIndicatorTargetsResponsePayload {
    val targets = reportStore.fetchCommonIndicatorTargets(projectId)
    return GetCommonIndicatorTargetsResponsePayload(
        targets.map { CommonIndicatorTargetsPayload(it) }
    )
  }

  @ApiResponse200
  @GetMapping("/autoCalculatedIndicatorTargets")
  @Operation(summary = "Get all auto calculated indicator targets for a project.")
  fun getAutoCalculatedIndicatorTargets(
      @PathVariable projectId: ProjectId
  ): GetAutoCalculatedIndicatorTargetsResponsePayload {
    val targets = reportStore.fetchAutoCalculatedIndicatorTargets(projectId)
    return GetAutoCalculatedIndicatorTargetsResponsePayload(
        targets.map { AutoCalculatedIndicatorTargetsPayload(it) }
    )
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/projectIndicatorTarget")
  @Operation(summary = "Update project indicator target for a year.")
  fun updateProjectIndicatorTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateProjectIndicatorTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateProjectIndicatorTarget(
        projectId = projectId,
        year = payload.year,
        indicatorId = payload.indicatorId,
        target = payload.target,
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/commonIndicatorTarget")
  @Operation(summary = "Update common indicator target for a year.")
  fun updateCommonIndicatorTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateCommonIndicatorTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateCommonIndicatorTarget(
        projectId = projectId,
        year = payload.year,
        indicatorId = payload.indicatorId,
        target = payload.target,
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/autoCalculatedIndicatorTarget")
  @Operation(summary = "Update auto calculated indicator target for a year.")
  fun updateAutoCalculatedIndicatorTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateAutoCalculatedIndicatorTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateAutoCalculatedIndicatorTarget(
        projectId = projectId,
        year = payload.year,
        indicatorId = payload.indicator,
        target = payload.target,
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/projectIndicatorTarget/baseline")
  @Operation(summary = "Update project indicator baseline and end of project target.")
  fun updateProjectIndicatorBaselineTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateProjectIndicatorBaselineTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateProjectIndicatorBaselineTarget(
        projectId = projectId,
        indicatorId = payload.indicatorId,
        baseline = payload.baseline,
        endOfProjectTarget = payload.endOfProjectTarget,
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/commonIndicatorTarget/baseline")
  @Operation(summary = "Update common indicator baseline and end of project target.")
  fun updateCommonIndicatorBaselineTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateCommonIndicatorBaselineTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateCommonIndicatorBaselineTarget(
        projectId = projectId,
        indicatorId = payload.indicatorId,
        baseline = payload.baseline,
        endOfProjectTarget = payload.endOfProjectTarget,
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/autoCalculatedIndicatorTarget/baseline")
  @Operation(summary = "Update auto calculated indicator baseline and end of project target.")
  fun updateAutoCalculatedIndicatorBaselineTarget(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateAutoCalculatedIndicatorBaselineTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.updateAutoCalculatedIndicatorBaselineTarget(
        projectId = projectId,
        indicator = payload.indicator,
        baseline = payload.baseline,
        endOfProjectTarget = payload.endOfProjectTarget,
    )
    return SimpleSuccessResponsePayload()
  }
}

data class ExistingAcceleratorReportConfigPayload(
    val configId: ProjectReportConfigId,
    val projectId: ProjectId,
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
    val logframeUrl: URI?,
) {
  constructor(
      model: ExistingProjectReportConfigModel
  ) : this(
      configId = model.id,
      projectId = model.projectId,
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
  fun toModel(projectId: ProjectId): NewProjectReportConfigModel =
      NewProjectReportConfigModel(
          id = null,
          logframeUrl = logframeUrl,
          projectId = projectId,
          reportingEndDate = reportingEndDate,
          reportingStartDate = reportingStartDate,
      )
}

data class SimpleUserPayload(
    val userId: UserId,
    val fullName: String,
) {
  constructor(model: SimpleUserModel) : this(model.userId, model.fullName)
}

data class AcceleratorReportPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val autoCalculatedIndicators: List<ReportAutoCalculatedIndicatorPayload>,
    val challenges: List<ReportChallengePayload>,
    val commonIndicators: List<ReportCommonIndicatorPayload>,
    val endDate: LocalDate,
    val feedback: String?,
    val financialSummaries: String?,
    val highlights: String?,
    val id: ReportId,
    val internalComment: String?,
    val modifiedBy: UserId,
    val modifiedByUser: SimpleUserPayload,
    val modifiedTime: Instant,
    val photos: List<ReportPhotoPayload>,
    val projectId: ProjectId,
    val projectIndicators: List<ReportProjectIndicatorPayload>,
    @Schema(description = "Use projectIndicators instead", deprecated = true)
    val projectMetrics: List<ReportProjectMetricPayload>,
    val quarter: ReportQuarter?,
    @Schema(description = "Use commonIndicators instead", deprecated = true)
    val standardMetrics: List<ReportStandardMetricPayload>,
    val startDate: LocalDate,
    val status: ReportStatus,
    val submittedBy: UserId?,
    val submittedByUser: SimpleUserPayload?,
    val submittedTime: Instant?,
    @Schema(description = "Use autoCalculatedIndicators instead", deprecated = true)
    val systemMetrics: List<ReportSystemMetricPayload>,
    val unpublishedProperties: List<PublishedReportComparedProps>,
) {
  constructor(
      model: ReportModel
  ) : this(
      achievements = model.achievements,
      additionalComments = model.additionalComments,
      autoCalculatedIndicators =
          model.autoCalculatedIndicators.map { ReportAutoCalculatedIndicatorPayload(it) },
      challenges = model.challenges.map { ReportChallengePayload(it) },
      commonIndicators = model.commonIndicators.map { ReportCommonIndicatorPayload(it) },
      endDate = model.endDate,
      feedback = model.feedback,
      financialSummaries = model.financialSummaries,
      highlights = model.highlights,
      id = model.id,
      internalComment = model.internalComment,
      modifiedBy = model.modifiedBy,
      modifiedByUser = SimpleUserPayload(model.modifiedByUser),
      modifiedTime = model.modifiedTime,
      photos = model.photos.map { ReportPhotoPayload(it) },
      projectId = model.projectId,
      projectIndicators = model.projectIndicators.map { ReportProjectIndicatorPayload(it) },
      projectMetrics = model.projectIndicators.map { ReportProjectMetricPayload(it) },
      quarter = model.quarter,
      standardMetrics = model.commonIndicators.map { ReportStandardMetricPayload(it) },
      startDate = model.startDate,
      status = model.status,
      submittedBy = model.submittedBy,
      submittedByUser = model.submittedByUser?.let { SimpleUserPayload(it) },
      submittedTime = model.submittedTime,
      systemMetrics = model.autoCalculatedIndicators.map { ReportSystemMetricPayload(it) },
      unpublishedProperties = model.unpublishedProperties,
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

@Schema(description = "Use ReportCommonIndicatorPayload instead", deprecated = true)
data class ReportStandardMetricPayload(
    val component: IndicatorCategory,
    val description: String?,
    val id: CommonIndicatorId,
    val isPublishable: Boolean,
    val name: String,
    val progressNotes: String?,
    val projectsComments: String?,
    val reference: String,
    val status: ReportIndicatorStatus?,
    val target: Int?,
    val type: IndicatorLevel,
    val value: Int?,
) {
  constructor(
      model: ReportCommonIndicatorModel
  ) : this(
      component = model.indicator.category,
      description = model.indicator.description,
      id = model.indicator.id,
      isPublishable = model.indicator.isPublishable,
      name = model.indicator.name,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      reference = model.indicator.refId,
      status = model.entry.status,
      target = model.entry.target?.toInt(),
      type = model.indicator.level,
      value = model.entry.value?.toInt(),
  )
}

data class CumulativeIndicatorProgressPayload(
    val quarter: ReportQuarter,
    val value: BigDecimal,
) {
  constructor(
      model: CumulativeIndicatorProgressModel
  ) : this(
      quarter = model.quarter,
      value = model.value,
  )
}

data class ReportCommonIndicatorPayload(
    val baseline: BigDecimal?,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    @Schema(
        description =
            "If the indicator is cumulative, the list of actual values for all quarters in the report's year"
    )
    val currentYearProgress: List<CumulativeIndicatorProgressPayload>?,
    val description: String?,
    val endOfProjectTarget: BigDecimal?,
    val id: CommonIndicatorId,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val precision: Int,
    @Schema(
        description =
            "If the indicator is cumulative, the cumulative total at the end of the previous year"
    )
    val previousYearCumulativeTotal: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val target: BigDecimal?,
    val value: BigDecimal?,
) {
  constructor(
      model: ReportCommonIndicatorModel
  ) : this(
      baseline = model.baseline,
      category = model.indicator.category,
      classId = model.indicator.classId,
      currentYearProgress =
          model.currentYearProgress?.map { CumulativeIndicatorProgressPayload(it) },
      description = model.indicator.description,
      endOfProjectTarget = model.endOfProjectTarget,
      id = model.indicator.id,
      isPublishable = model.indicator.isPublishable,
      level = model.indicator.level,
      name = model.indicator.name,
      precision = model.indicator.precision,
      previousYearCumulativeTotal = model.previousYearCumulativeTotal,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      refId = model.indicator.refId,
      status = model.entry.status,
      target = model.entry.target,
      value = model.entry.value,
  )
}

@Schema(description = "Use ReportCommonIndicatorEntriesPayload instead", deprecated = true)
data class ReportStandardMetricEntriesPayload(
    val id: CommonIndicatorId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
    val value: Int?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          value = value?.toBigDecimal(),
      )
}

data class ReportCommonIndicatorEntriesPayload(
    val id: CommonIndicatorId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
    val value: BigDecimal?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          value = value,
      )
}

@Schema(description = "Use ReportAutoCalculatedIndicatorPayload instead", deprecated = true)
data class ReportSystemMetricPayload(
    val component: IndicatorCategory,
    val description: String?,
    val isPublishable: Boolean,
    val metric: AutoCalculatedIndicator,
    val overrideValue: Int?,
    val progressNotes: String?,
    val projectsComments: String?,
    val reference: String,
    val status: ReportIndicatorStatus?,
    val systemTime: Instant?,
    val systemValue: Int?,
    val target: Int?,
    val type: IndicatorLevel,
) {
  constructor(
      model: ReportAutoCalculatedIndicatorModel
  ) : this(
      component = model.indicator.categoryId,
      description = model.indicator.description,
      isPublishable = model.indicator.isPublishable,
      metric = model.indicator,
      overrideValue = model.entry.overrideValue?.toInt(),
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      reference = model.indicator.refId,
      status = model.entry.status,
      systemTime = model.entry.systemTime,
      systemValue = model.entry.systemValue?.toInt(),
      target = model.entry.target?.toInt(),
      type = model.indicator.levelId,
  )
}

data class ReportAutoCalculatedIndicatorPayload(
    val baseline: BigDecimal?,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    @Schema(
        description =
            "If the indicator is cumulative, the list of actual values for all quarters in the report's year"
    )
    val currentYearProgress: List<CumulativeIndicatorProgressPayload>?,
    val description: String?,
    val endOfProjectTarget: BigDecimal?,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val indicator: AutoCalculatedIndicator,
    val overrideValue: BigDecimal?,
    val precision: Int,
    @Schema(
        description =
            "If the indicator is cumulative, the cumulative total at the end of the previous year"
    )
    val previousYearCumulativeTotal: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val systemTime: Instant?,
    val systemValue: BigDecimal?,
    val target: BigDecimal?,
) {
  constructor(
      model: ReportAutoCalculatedIndicatorModel
  ) : this(
      baseline = model.baseline,
      category = model.indicator.categoryId,
      classId = model.indicator.classId,
      currentYearProgress =
          model.currentYearProgress?.map { CumulativeIndicatorProgressPayload(it) },
      description = model.indicator.description,
      endOfProjectTarget = model.endOfProjectTarget,
      isPublishable = model.indicator.isPublishable,
      level = model.indicator.levelId,
      indicator = model.indicator,
      overrideValue = model.entry.overrideValue,
      precision = model.indicator.precision,
      previousYearCumulativeTotal = model.previousYearCumulativeTotal,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      refId = model.indicator.refId,
      status = model.entry.status,
      systemTime = model.entry.systemTime,
      systemValue = model.entry.systemValue,
      target = model.entry.target,
  )
}

@Schema(description = "Use ReportAutoCalculatedIndicatorEntriesPayload instead", deprecated = true)
data class ReportSystemMetricEntriesPayload(
    val metric: AutoCalculatedIndicator,
    val overrideValue: Int?,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          value = overrideValue?.toBigDecimal(),
      )
}

data class ReportAutoCalculatedIndicatorEntriesPayload(
    val indicator: AutoCalculatedIndicator,
    val overrideValue: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
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

@Schema(description = "Use ReportProjectIndicatorPayload instead", deprecated = true)
data class ReportProjectMetricPayload(
    val component: IndicatorCategory,
    val description: String?,
    val id: ProjectIndicatorId,
    val isPublishable: Boolean,
    val name: String,
    val progressNotes: String?,
    val projectsComments: String?,
    val reference: String,
    val status: ReportIndicatorStatus?,
    val target: Int?,
    val type: IndicatorLevel,
    val unit: String?,
    val value: Int?,
) {
  constructor(
      model: ReportProjectIndicatorModel
  ) : this(
      component = model.indicator.category,
      description = model.indicator.description,
      id = model.indicator.id,
      isPublishable = model.indicator.isPublishable,
      name = model.indicator.name,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      reference = model.indicator.refId,
      status = model.entry.status,
      target = model.entry.target?.toInt(),
      type = model.indicator.level,
      unit = model.indicator.unit,
      value = model.entry.value?.toInt(),
  )
}

data class ReportProjectIndicatorPayload(
    val baseline: BigDecimal?,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    @Schema(
        description =
            "If the indicator is cumulative, the list of actual values for all quarters in the report's year"
    )
    val currentYearProgress: List<CumulativeIndicatorProgressPayload>?,
    val description: String?,
    val endOfProjectTarget: BigDecimal?,
    val id: ProjectIndicatorId,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val precision: Int,
    @Schema(
        description =
            "If the indicator is cumulative, the cumulative total at the end of the previous year"
    )
    val previousYearCumulativeTotal: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val target: BigDecimal?,
    val unit: String?,
    val value: BigDecimal?,
) {
  constructor(
      model: ReportProjectIndicatorModel
  ) : this(
      baseline = model.baseline,
      category = model.indicator.category,
      classId = model.indicator.classId,
      currentYearProgress =
          model.currentYearProgress?.map { CumulativeIndicatorProgressPayload(it) },
      description = model.indicator.description,
      endOfProjectTarget = model.endOfProjectTarget,
      id = model.indicator.id,
      isPublishable = model.indicator.isPublishable,
      level = model.indicator.level,
      name = model.indicator.name,
      precision = model.indicator.precision,
      previousYearCumulativeTotal = model.previousYearCumulativeTotal,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      refId = model.indicator.refId,
      status = model.entry.status,
      target = model.entry.target,
      unit = model.indicator.unit,
      value = model.entry.value,
  )
}

@Schema(description = "Use ReportProjectIndicatorEntriesPayload instead", deprecated = true)
data class ReportProjectMetricEntriesPayload(
    val id: ProjectIndicatorId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
    val value: Int?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          value = value?.toBigDecimal(),
      )
}

data class ReportProjectIndicatorEntriesPayload(
    val id: ProjectIndicatorId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
    val value: BigDecimal?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          value = value,
      )
}

data class ReportYearsPayload(val startYear: Int, val endYear: Int)

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

@Schema(
    description = "Use ReviewAcceleratorReportIndicatorsRequestPayload instead",
    deprecated = true,
)
data class ReviewAcceleratorReportMetricsRequestPayload(
    val projectMetrics: List<ReportProjectMetricEntriesPayload>,
    val standardMetrics: List<ReportStandardMetricEntriesPayload>,
    val systemMetrics: List<ReportSystemMetricEntriesPayload>,
)

data class ReviewAcceleratorReportIndicatorsRequestPayload(
    val autoCalculatedIndicators: List<ReportAutoCalculatedIndicatorEntriesPayload>,
    val commonIndicators: List<ReportCommonIndicatorEntriesPayload>,
    val projectIndicators: List<ReportProjectIndicatorEntriesPayload>,
)

data class UpdateAcceleratorReportValuesRequestPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val autoCalculatedIndicators: List<ReportAutoCalculatedIndicatorEntriesPayload>?,
    val challenges: List<ReportChallengePayload>,
    val commonIndicators: List<ReportCommonIndicatorEntriesPayload>?,
    val financialSummaries: String?,
    val highlights: String?,
    val projectIndicators: List<ReportProjectIndicatorEntriesPayload>?,
    @Schema(description = "Use projectIndicators instead", deprecated = true)
    val projectMetrics: List<ReportProjectMetricEntriesPayload>?,
    @Schema(description = "Use commonIndicators instead", deprecated = true)
    val standardMetrics: List<ReportStandardMetricEntriesPayload>?,
    @Schema(description = "Use autoCalculatedIndicators instead", deprecated = true)
    val systemMetrics: List<ReportSystemMetricEntriesPayload>?,
)

@Schema(description = "Use UpdateProjectIndicatorTargetRequestPayload instead", deprecated = true)
data class UpdateProjectMetricTargetRequestPayload(
    val year: Int,
    val metricId: ProjectIndicatorId,
    val target: Int?,
)

@Schema(description = "Use UpdateCommonIndicatorTargetRequestPayload instead", deprecated = true)
data class UpdateStandardMetricTargetRequestPayload(
    val year: Int,
    val metricId: CommonIndicatorId,
    val target: Int?,
)

@Schema(
    description = "Use UpdateAutoCalculatedIndicatorTargetRequestPayload instead",
    deprecated = true,
)
data class UpdateSystemMetricTargetRequestPayload(
    val year: Int,
    val metric: AutoCalculatedIndicator,
    val target: Int?,
)

data class UpdateProjectIndicatorTargetRequestPayload(
    val year: Int,
    val indicatorId: ProjectIndicatorId,
    val target: BigDecimal?,
)

data class UpdateCommonIndicatorTargetRequestPayload(
    val year: Int,
    val indicatorId: CommonIndicatorId,
    val target: BigDecimal?,
)

data class UpdateAutoCalculatedIndicatorTargetRequestPayload(
    val year: Int,
    val indicator: AutoCalculatedIndicator,
    val target: BigDecimal?,
)

data class UpdateProjectIndicatorBaselineTargetRequestPayload(
    val indicatorId: ProjectIndicatorId,
    val baseline: BigDecimal?,
    val endOfProjectTarget: BigDecimal?,
)

data class UpdateCommonIndicatorBaselineTargetRequestPayload(
    val indicatorId: CommonIndicatorId,
    val baseline: BigDecimal?,
    val endOfProjectTarget: BigDecimal?,
)

data class UpdateAutoCalculatedIndicatorBaselineTargetRequestPayload(
    val indicator: AutoCalculatedIndicator,
    val baseline: BigDecimal?,
    val endOfProjectTarget: BigDecimal?,
)

data class ListAcceleratorReportsResponsePayload(val reports: List<AcceleratorReportPayload>) :
    SuccessResponsePayload

data class GetAcceleratorReportResponsePayload(val report: AcceleratorReportPayload) :
    SuccessResponsePayload

data class GetAcceleratorReportYearsResponsePayload(val years: ReportYearsPayload?) :
    SuccessResponsePayload

data class ListAcceleratorReportConfigResponsePayload(
    val configs: List<ExistingAcceleratorReportConfigPayload>
) : SuccessResponsePayload

@Schema(description = "Use ListProjectIndicatorsResponsePayload instead", deprecated = true)
data class ListProjectMetricsResponsePayload(val metrics: List<ExistingProjectMetricPayload>) :
    SuccessResponsePayload

@Schema(description = "Use CreateProjectIndicatorRequestPayload instead", deprecated = true)
data class CreateProjectMetricRequestPayload(@field:Valid val metric: NewMetricPayload)

@Schema(description = "Use UpdateProjectIndicatorRequestPayload instead", deprecated = true)
data class UpdateProjectMetricRequestPayload(@field:Valid val metric: ExistingProjectMetricPayload)

data class ListProjectIndicatorsResponsePayload(
    val indicators: List<ExistingProjectIndicatorPayload>
) : SuccessResponsePayload

data class CreateProjectIndicatorRequestPayload(@field:Valid val indicator: NewIndicatorPayload)

data class UpdateProjectIndicatorRequestPayload(
    @field:Valid val indicator: ExistingProjectIndicatorPayload
)

data class UpdateAcceleratorReportPhotoRequestPayload(val caption: String?)

data class UploadAcceleratorReportPhotoResponsePayload(val fileId: FileId) : SuccessResponsePayload

@Schema(description = "Use ProjectIndicatorTargetsPayload instead", deprecated = true)
data class ReportProjectMetricTargetPayload(
    val metricId: ProjectIndicatorId,
    val target: Number?,
    val year: Number,
) {
  constructor(
      model: ReportProjectIndicatorTargetModel
  ) : this(
      metricId = model.indicatorId,
      target = model.target,
      year = model.year,
  )
}

@Schema(description = "Use CommonIndicatorTargetsPayload instead", deprecated = true)
data class ReportStandardMetricTargetPayload(
    val metricId: CommonIndicatorId,
    val target: Number?,
    val year: Number,
) {
  constructor(
      model: ReportCommonIndicatorTargetModel
  ) : this(
      metricId = model.indicatorId,
      target = model.target,
      year = model.year,
  )
}

@Schema(description = "Use AutoCalculatedIndicatorTargetsPayload instead", deprecated = true)
data class ReportSystemMetricTargetPayload(
    val metric: AutoCalculatedIndicator,
    val target: Number?,
    val year: Number,
) {
  constructor(
      model: ReportAutoCalculatedIndicatorTargetModel
  ) : this(
      metric = model.indicator,
      target = model.target,
      year = model.year,
  )
}

@Schema(description = "Use GetProjectIndicatorTargetsResponsePayload instead", deprecated = true)
data class GetProjectMetricTargetsResponsePayload(
    val targets: List<ReportProjectMetricTargetPayload>
) : SuccessResponsePayload

@Schema(description = "Use GetCommonIndicatorTargetsResponsePayload instead", deprecated = true)
data class GetStandardMetricTargetsResponsePayload(
    val targets: List<ReportStandardMetricTargetPayload>
) : SuccessResponsePayload

@Schema(
    description = "Use GetAutoCalculatedIndicatorTargetsResponsePayload instead",
    deprecated = true,
)
data class GetSystemMetricTargetsResponsePayload(
    val targets: List<ReportSystemMetricTargetPayload>
) : SuccessResponsePayload

data class YearlyIndicatorTargetPayload(
    val target: Number?,
    val year: Number,
) {
  constructor(model: YearlyIndicatorTargetModel) : this(target = model.target, year = model.year)
}

data class ProjectIndicatorTargetsPayload(
    val indicatorId: ProjectIndicatorId,
    val baseline: BigDecimal?,
    val endOfProjectTarget: BigDecimal?,
    val yearlyTargets: List<YearlyIndicatorTargetPayload>,
) {
  constructor(
      model: ProjectIndicatorTargetsModel
  ) : this(
      indicatorId = model.indicatorId,
      baseline = model.baseline,
      endOfProjectTarget = model.endOfProjectTarget,
      yearlyTargets = model.yearlyTargets.map { YearlyIndicatorTargetPayload(it) },
  )
}

data class CommonIndicatorTargetsPayload(
    val indicatorId: CommonIndicatorId,
    val baseline: BigDecimal?,
    val endOfProjectTarget: BigDecimal?,
    val yearlyTargets: List<YearlyIndicatorTargetPayload>,
) {
  constructor(
      model: CommonIndicatorTargetsModel
  ) : this(
      indicatorId = model.indicatorId,
      baseline = model.baseline,
      endOfProjectTarget = model.endOfProjectTarget,
      yearlyTargets = model.yearlyTargets.map { YearlyIndicatorTargetPayload(it) },
  )
}

data class AutoCalculatedIndicatorTargetsPayload(
    val indicatorId: AutoCalculatedIndicator,
    val baseline: BigDecimal?,
    val endOfProjectTarget: BigDecimal?,
    val yearlyTargets: List<YearlyIndicatorTargetPayload>,
) {
  constructor(
      model: AutoCalculatedIndicatorTargetsModel
  ) : this(
      indicatorId = model.indicatorId,
      baseline = model.baseline,
      endOfProjectTarget = model.endOfProjectTarget,
      yearlyTargets = model.yearlyTargets.map { YearlyIndicatorTargetPayload(it) },
  )
}

data class GetProjectIndicatorTargetsResponsePayload(
    val targets: List<ProjectIndicatorTargetsPayload>
) : SuccessResponsePayload

data class GetCommonIndicatorTargetsResponsePayload(
    val targets: List<CommonIndicatorTargetsPayload>
) : SuccessResponsePayload

data class GetAutoCalculatedIndicatorTargetsResponsePayload(
    val targets: List<AutoCalculatedIndicatorTargetsPayload>
) : SuccessResponsePayload
