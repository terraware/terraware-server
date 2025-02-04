package com.terraformation.backend.report.event

import com.terraformation.backend.report.model.SeedFundReportMetadata

data class SeedFundReportCreatedEvent(val metadata: SeedFundReportMetadata)
