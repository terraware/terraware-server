package com.terraformation.backend.report.event

import com.terraformation.backend.report.model.ReportMetadata

data class ReportCreatedEvent(val metadata: ReportMetadata)
