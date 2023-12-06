package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId

data class ReportProjectSettingsModel(
    val projectId: ProjectId,
    val isConfigured: Boolean = true,
    val isEnabled: Boolean,
)

data class ReportSettingsModel(
    /**
     * Whether or not the organization's settings have been configured. If false, settings are the
     * defaults.
     */
    val isConfigured: Boolean = true,
    /**
     * Whether or not organization-level reports are enabled. If this is false, reports might still
     * be enabled for projects.
     */
    val organizationEnabled: Boolean,
    val organizationId: OrganizationId,
    val projects: List<ReportProjectSettingsModel>,
)
