package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.default_schema.ProjectId

data class AcceleratorReportSubmittedEvent(
    val reportId: ReportId,
    val projectId: ProjectId,
)
