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
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportMetricStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.funder.PublishedReportService
import com.terraformation.backend.funder.db.PublishedReportStore
import com.terraformation.backend.funder.model.PublishedReportMetricModel
import com.terraformation.backend.funder.model.PublishedReportModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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

data class PublishedReportMetricPayload(
    val component: MetricComponent,
    val description: String?,
    val name: String,
    val progressNotes: String?,
    val reference: String,
    val status: ReportMetricStatus?,
    val target: Int?,
    val type: MetricType,
    val underperformanceJustification: String?,
    val value: Int?,
    val unit: String?,
) {
  constructor(
      model: PublishedReportMetricModel<*>
  ) : this(
      component = model.component,
      description = model.description,
      name = model.name,
      progressNotes = model.progressNotes,
      reference = model.reference,
      status = model.status,
      target = model.target,
      type = model.type,
      underperformanceJustification = model.underperformanceJustification,
      value = model.value,
      unit = model.unit,
  )
}

data class PublishedReportPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val challenges: List<ReportChallengePayload>,
    val endDate: LocalDate,
    val financialSummaries: String?,
    val frequency: ReportFrequency,
    val highlights: String?,
    val photos: List<ReportPhotoPayload>,
    val projectId: ProjectId,
    val projectMetrics: List<PublishedReportMetricPayload>,
    val projectName: String,
    val publishedBy: UserId,
    val publishedTime: Instant,
    val quarter: ReportQuarter?,
    val reportId: ReportId,
    val standardMetrics: List<PublishedReportMetricPayload>,
    val startDate: LocalDate,
    val systemMetrics: List<PublishedReportMetricPayload>,
) {
  constructor(
      model: PublishedReportModel
  ) : this(
      achievements = model.achievements,
      additionalComments = model.additionalComments,
      challenges = model.challenges.map { ReportChallengePayload(it.challenge, it.mitigationPlan) },
      endDate = model.endDate,
      financialSummaries = model.financialSummaries,
      frequency = model.frequency,
      highlights = model.highlights,
      photos = model.photos.map { ReportPhotoPayload(it) },
      projectId = model.projectId,
      projectMetrics = model.projectMetrics.map { PublishedReportMetricPayload(it) },
      projectName = model.projectName,
      publishedBy = model.publishedBy,
      publishedTime = model.publishedTime,
      quarter = model.quarter,
      reportId = model.reportId,
      standardMetrics = model.standardMetrics.map { PublishedReportMetricPayload(it) },
      startDate = model.startDate,
      systemMetrics = model.systemMetrics.map { PublishedReportMetricPayload(it) },
  )
}

data class ListPublishedReportsResponsePayload(val reports: List<PublishedReportPayload>) :
    SuccessResponsePayload
