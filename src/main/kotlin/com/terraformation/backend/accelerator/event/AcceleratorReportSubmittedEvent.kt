package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ReportId

data class AcceleratorReportSubmittedEvent(val reportId: ReportId)
