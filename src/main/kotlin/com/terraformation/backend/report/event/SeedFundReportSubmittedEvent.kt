package com.terraformation.backend.report.event

import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.report.model.LatestSeedFundReportBodyModel

/** Published when an organization admin submits a report. */
data class SeedFundReportSubmittedEvent(
    val reportId: SeedFundReportId,
    val body: LatestSeedFundReportBodyModel,
)
