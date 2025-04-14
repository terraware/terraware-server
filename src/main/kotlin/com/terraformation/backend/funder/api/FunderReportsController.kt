package com.terraformation.backend.funder.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.funder.db.PublishedReportsStore
import com.terraformation.backend.funder.model.PublishedReportModel
import io.swagger.v3.oas.annotations.Operation
import java.time.Instant
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@FunderEndpoint
@RequestMapping("/api/v1/funder/reports")
@RestController
class FunderReportsController(
    private val publishedReportsStore: PublishedReportsStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/projects/{projectId}")
  @Operation(summary = "Get the published reports for a specific project.")
  fun listPublishedReports(
      @PathVariable projectId: ProjectId,
  ): ListPublishedReportsResponsePayload {
    val reports = publishedReportsStore.fetchPublishedReports(projectId)
    return ListPublishedReportsResponsePayload(reports.map { PublishedReportPayload(it) })
  }
}

data class ReportChallengePayload(
    val challenge: String,
    val mitigationPlan: String,
)

data class PublishedReportPayload(
    val achievements: List<String>,
    val challenges: List<ReportChallengePayload>,
    val endDate: LocalDate,
    val frequency: ReportFrequency,
    val highlights: String?,
    val projectId: ProjectId,
    val projectName: String,
    val publishedBy: UserId,
    val publishedTime: Instant,
    val quarter: ReportQuarter?,
    val reportId: ReportId,
    val startDate: LocalDate,
) {
  constructor(
      model: PublishedReportModel
  ) : this(
      achievements = model.achievements,
      challenges = model.challenges.map { ReportChallengePayload(it.challenge, it.mitigationPlan) },
      endDate = model.endDate,
      frequency = model.frequency,
      highlights = model.highlights,
      projectId = model.projectId,
      projectName = model.projectName,
      publishedBy = model.publishedBy,
      publishedTime = model.publishedTime,
      quarter = model.quarter,
      reportId = model.reportId,
      startDate = model.startDate,
  )
}

data class ListPublishedReportsResponsePayload(val reports: List<PublishedReportPayload>) :
    SuccessResponsePayload
