package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate

data class ReportModel<ID : ReportId?>(
    val id: ID,
    val configId: ProjectReportConfigId,
    val projectId: ProjectId,
    val status: ReportStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val internalComment: String? = null,
    val feedback: String? = null,
    val createdBy: UserId,
    val createdtime: Instant,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val submittedBy: UserId?,
    val submittedTime: Instant?,
)

typealias ExistingReportModel = ReportModel<ReportId>

typealias NewReportModel = ReportModel<Nothing?>
