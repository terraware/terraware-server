package com.terraformation.backend.funder.api

import com.terraformation.backend.accelerator.api.ReportPhotoPayload
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.funder.PublishedReportService
import com.terraformation.backend.funder.db.PublishedReportStore
import com.terraformation.backend.funder.model.PublishedReportIndicatorModel
import com.terraformation.backend.funder.model.PublishedReportModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

@FunderEndpoint
@RequestMapping("/api/v1/funder/reports")
@RestController
class FunderReportsController(
    private val publishedReportService: PublishedReportService,
    private val publishedReportStore: PublishedReportStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/projects/{projectId}")
  @Operation(summary = "Get the published reports for a specific project.")
  fun listPublishedReports(
      @PathVariable projectId: ProjectId,
  ): ListPublishedReportsResponsePayload {
    val reports = publishedReportStore.fetchPublishedReports(projectId)
    return ListPublishedReportsResponsePayload(reports.map { PublishedReportPayload(it) })
  }

  @ApiResponse200Photo
  @ApiResponse404("The report does not exist, or does not have a photo with the requested ID.")
  @GetMapping(
      "/{reportId}/photos/{fileId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE],
  )
  @Operation(
      summary = "Retrieves a specific photo from a published report",
      description = PHOTO_OPERATION_DESCRIPTION,
  )
  @ResponseBody
  fun getPublishedReportPhoto(
      @PathVariable reportId: ReportId,
      @PathVariable fileId: FileId,
      @Parameter(description = PHOTO_MAXWIDTH_DESCRIPTION) @RequestParam maxWidth: Int? = null,
      @Parameter(description = PHOTO_MAXHEIGHT_DESCRIPTION) @RequestParam maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return publishedReportService
        .readPhoto(reportId, fileId, maxWidth, maxHeight)
        .toResponseEntity()
  }
}

data class ReportChallengePayload(
    val challenge: String,
    val mitigationPlan: String,
)

@Schema(description = "Use PublishedReportIndicatorPayload instead", deprecated = true)
data class PublishedReportMetricPayload(
    val component: IndicatorCategory,
    val description: String?,
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
      model: PublishedReportIndicatorModel<*>
  ) : this(
      component = model.category,
      description = model.description,
      name = model.name,
      progressNotes = model.progressNotes,
      projectsComments = model.projectsComments,
      reference = model.refId,
      status = model.status,
      target = model.target,
      type = model.level,
      unit = model.unit,
      value = model.value,
  )
}

data class PublishedReportIndicatorPayload(
    val baseline: BigDecimal?,
    val category: IndicatorCategory,
    val classId: IndicatorClass?,
    val description: String?,
    val endOfProjectTarget: BigDecimal?,
    val level: IndicatorLevel,
    val name: String,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val target: Int?,
    val unit: String?,
    val value: Int?,
) {
  constructor(
      model: PublishedReportIndicatorModel<*>
  ) : this(
      baseline = model.baseline,
      category = model.category,
      classId = model.classId,
      description = model.description,
      endOfProjectTarget = model.endOfProjectTarget,
      level = model.level,
      name = model.name,
      progressNotes = model.progressNotes,
      projectsComments = model.projectsComments,
      refId = model.refId,
      status = model.status,
      target = model.target,
      unit = model.unit,
      value = model.value,
  )
}

data class PublishedReportPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val autoCalculatedIndicators: List<PublishedReportIndicatorPayload>,
    val challenges: List<ReportChallengePayload>,
    val commonIndicators: List<PublishedReportIndicatorPayload>,
    val endDate: LocalDate,
    val financialSummaries: String?,
    val highlights: String?,
    val photos: List<ReportPhotoPayload>,
    val projectId: ProjectId,
    val projectIndicators: List<PublishedReportIndicatorPayload>,
    @Schema(description = "Use projectIndicators instead", deprecated = true)
    val projectMetrics: List<PublishedReportMetricPayload>,
    val projectName: String,
    val publishedBy: UserId,
    val publishedTime: Instant,
    val quarter: ReportQuarter?,
    val reportId: ReportId,
    @Schema(description = "Use commonIndicators instead", deprecated = true)
    val standardMetrics: List<PublishedReportMetricPayload>,
    val startDate: LocalDate,
    @Schema(description = "Use autoCalculatedIndicators instead", deprecated = true)
    val systemMetrics: List<PublishedReportMetricPayload>,
) {
  constructor(
      model: PublishedReportModel
  ) : this(
      achievements = model.achievements,
      additionalComments = model.additionalComments,
      autoCalculatedIndicators =
          model.autoCalculatedIndicators.map { PublishedReportIndicatorPayload(it) },
      challenges = model.challenges.map { ReportChallengePayload(it.challenge, it.mitigationPlan) },
      commonIndicators = model.commonIndicators.map { PublishedReportIndicatorPayload(it) },
      endDate = model.endDate,
      financialSummaries = model.financialSummaries,
      highlights = model.highlights,
      photos = model.photos.map { ReportPhotoPayload(it) },
      projectId = model.projectId,
      projectIndicators = model.projectIndicators.map { PublishedReportIndicatorPayload(it) },
      projectMetrics = model.projectIndicators.map { PublishedReportMetricPayload(it) },
      projectName = model.projectName,
      publishedBy = model.publishedBy,
      publishedTime = model.publishedTime,
      quarter = model.quarter,
      reportId = model.reportId,
      standardMetrics = model.commonIndicators.map { PublishedReportMetricPayload(it) },
      startDate = model.startDate,
      systemMetrics = model.autoCalculatedIndicators.map { PublishedReportMetricPayload(it) },
  )
}

data class ListPublishedReportsResponsePayload(val reports: List<PublishedReportPayload>) :
    SuccessResponsePayload
