package com.terraformation.backend.report.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.ReportProjectSettingsModel
import com.terraformation.backend.report.model.ReportSettingsModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/reports/settings")
@RestController
class ReportsSettingsController(
    private val reportStore: ReportStore,
) {
  @GetMapping
  @Operation(summary = "Gets the report settings for an organization.")
  fun getReportSettings(
      @RequestParam organizationId: OrganizationId
  ): GetReportSettingsResponsePayload {
    val settings = reportStore.fetchSettingsByOrganization(organizationId)

    return GetReportSettingsResponsePayload(settings)
  }

  @PutMapping
  @Operation(summary = "Updates the report settings for an organization.")
  fun updateReportSettings(
      @RequestBody payload: UpdateReportSettingsRequestPayload
  ): SimpleSuccessResponsePayload {
    reportStore.updateSettings(payload.toModel())

    return SimpleSuccessResponsePayload()
  }
}

data class ProjectReportSettingsPayload(
    val projectId: ProjectId,
    @Schema(
        description = "If true, reports are enabled for this project.",
    )
    val isEnabled: Boolean,
) {
  constructor(model: ReportProjectSettingsModel) : this(model.projectId, model.isEnabled)

  fun toModel() =
      ReportProjectSettingsModel(
          projectId = projectId,
          isEnabled = isEnabled,
      )
}

data class GetReportSettingsResponsePayload(
    @Schema(
        description =
            "If false, settings have not been configured yet and the values in the rest of the " +
                "payload are the defaults.")
    val isConfigured: Boolean,
    @Schema(description = "If true, organization-level reports are enabled.")
    val organizationEnabled: Boolean,
    @ArraySchema(arraySchema = Schema(description = "Per-project report settings."))
    val projects: List<ProjectReportSettingsPayload>,
) {
  constructor(
      model: ReportSettingsModel
  ) : this(
      isConfigured = model.isConfigured,
      organizationEnabled = model.organizationEnabled,
      projects = model.projects.map { ProjectReportSettingsPayload(it) },
  )
}

data class UpdateReportSettingsRequestPayload(
    val organizationId: OrganizationId,
    @Schema(description = "If true, enable organization-level reports.")
    val organizationEnabled: Boolean,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "Per-project report settings. If a project is missing from this list, its " +
                        "settings will revert to the defaults."))
    val projects: List<ProjectReportSettingsPayload>,
) {
  fun toModel() =
      ReportSettingsModel(
          isConfigured = true,
          organizationId = organizationId,
          organizationEnabled = organizationEnabled,
          projects = projects.map { it.toModel() })
}
