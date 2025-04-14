package com.terraformation.backend.funder.model

import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate

/** A report that has been published to funders. */
data class PublishedReportModel(
    val achievements: List<String>,
    val challenges: List<ReportChallengeModel>,
    val endDate: LocalDate,
    val frequency: ReportFrequency,
    val highlights: String?,
    val projectId: ProjectId,
    val projectName: String,
    val publishedBy: UserId,
    val publishedTime: Instant,
    val quarter: ReportQuarter?,
    val reportId: ReportId,
    val startDate: LocalDate
)
