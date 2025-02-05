package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import java.time.Instant
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/reports")
@RestController
class ReportsController(
    private val reportStore: ReportStore,
) {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "List accelerator reports.",
      description = "List all reports, optionally filtered by project ID, and/or by year.")
  fun listAcceleratorReports(
      @RequestParam("projectId") projectId: ProjectId? = null,
      @RequestParam("year") year: Int? = null,
  ): ListAcceleratorReportsResponsePayload {
    val reports = reportStore.fetch(projectId, year)
    return ListAcceleratorReportsResponsePayload(reports.map { AcceleratorReportPayload(it) })
  }

  @ApiResponse200
  @GetMapping("/configs")
  @Operation(summary = "List accelerator report configurations.")
  fun listAcceleratorReportConfig(
      @RequestParam("projectId") projectId: ProjectId? = null,
  ): ListAcceleratorReportConfigResponsePayload {
    val configs = reportStore.fetchProjectReportConfigs(projectId)
    return ListAcceleratorReportConfigResponsePayload(
        configs.map { ExistingAcceleratorReportConfigPayload(it) })
  }

  @ApiResponse200
  @PutMapping("/configs")
  @Operation(
      summary = "Insert accelerator report configuration.",
      description =
          "Set up an accelerator report configuration for a project. This will create" +
              "all the reports within the reporting period.")
  fun createAcceleratorReportConfig(
      @RequestBody payload: CreateAcceleratorReportConfigRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.insertProjectReportConfig(payload.config.toModel())
    return SimpleSuccessResponsePayload()
  }
}

data class ExistingAcceleratorReportConfigPayload(
    val configId: ProjectReportConfigId,
    val projectId: ProjectId,
    val frequency: ReportFrequency,
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
) {
  constructor(
      model: ExistingProjectReportConfigModel
  ) : this(
      configId = model.id,
      projectId = model.projectId,
      frequency = model.frequency,
      reportingStartDate = model.reportingStartDate,
      reportingEndDate = model.reportingEndDate,
  )
}

data class NewAcceleratorReportConfigPayload(
    val projectId: ProjectId,
    val frequency: ReportFrequency,
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
) {
  fun toModel(): NewProjectReportConfigModel =
      NewProjectReportConfigModel(
          id = null,
          projectId = projectId,
          frequency = frequency,
          reportingStartDate = reportingStartDate,
          reportingEndDate = reportingEndDate,
      )
}

data class AcceleratorReportPayload(
    val id: ReportId,
    val projectId: ProjectId,
    val status: ReportStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val internalComment: String? = null,
    val feedback: String? = null,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val submittedBy: UserId?,
    val submittedTime: Instant?,
) {
  constructor(
      model: ReportModel
  ) : this(
      id = model.id,
      projectId = model.projectId,
      status = model.status,
      startDate = model.startDate,
      endDate = model.endDate,
      internalComment = model.internalComment,
      feedback = model.feedback,
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      submittedBy = model.submittedBy,
      submittedTime = model.submittedTime,
  )
}

data class ListAcceleratorReportsResponsePayload(val reports: List<AcceleratorReportPayload>) :
    SuccessResponsePayload

data class ListAcceleratorReportConfigResponsePayload(
    val configs: List<ExistingAcceleratorReportConfigPayload>
) : SuccessResponsePayload

data class CreateAcceleratorReportConfigRequestPayload(
    val config: NewAcceleratorReportConfigPayload
)
