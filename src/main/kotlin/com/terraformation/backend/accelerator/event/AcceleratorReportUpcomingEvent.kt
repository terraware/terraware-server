package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ReportId

data class AcceleratorReportUpcomingEvent(val reportId: ReportId)
