package com.terraformation.backend.funder.model

import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate

/** A report that has been published to funders. */
data class PublishedReportModel(
    val achievements: List<String>,
    val additionalComments: String?,
    val challenges: List<ReportChallengeModel>,
    val endDate: LocalDate,
    val financialSummaries: String?,
    val frequency: ReportFrequency,
    val highlights: String?,
    val projectId: ProjectId,
    val projectMetrics: List<PublishedReportMetricModel<ProjectMetricId>>,
    val projectName: String,
    val publishedBy: UserId,
    val publishedTime: Instant,
    val quarter: ReportQuarter?,
    val reportId: ReportId,
    val standardMetrics: List<PublishedReportMetricModel<StandardMetricId>>,
    val startDate: LocalDate,
    val systemMetrics: List<PublishedReportMetricModel<SystemMetric>>,
)
