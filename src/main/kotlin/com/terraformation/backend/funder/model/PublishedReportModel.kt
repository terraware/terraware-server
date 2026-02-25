package com.terraformation.backend.funder.model

import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate

/** A report that has been published to funders. */
data class PublishedReportModel(
    val achievements: List<String>,
    val additionalComments: String?,
    val autoCalculatedIndicators: List<PublishedReportIndicatorModel<AutoCalculatedIndicator>>,
    val challenges: List<ReportChallengeModel>,
    val commonIndicators: List<PublishedReportIndicatorModel<CommonIndicatorId>>,
    val endDate: LocalDate,
    val financialSummaries: String?,
    val highlights: String?,
    val photos: List<ReportPhotoModel>,
    val projectId: ProjectId,
    val projectIndicators: List<PublishedReportIndicatorModel<ProjectIndicatorId>>,
    val projectName: String,
    val publishedBy: UserId,
    val publishedTime: Instant,
    val quarter: ReportQuarter?,
    val reportId: ReportId,
    val startDate: LocalDate,
)
