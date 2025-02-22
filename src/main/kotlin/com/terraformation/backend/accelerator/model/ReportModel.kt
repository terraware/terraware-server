package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class ReportModel(
    val id: ReportId,
    val configId: ProjectReportConfigId,
    val projectId: ProjectId,
    val status: ReportStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val internalComment: String? = null,
    val feedback: String? = null,
    val createdBy: UserId,
    val createdTime: Instant,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val submittedBy: UserId? = null,
    val submittedTime: Instant? = null,
    val standardMetrics: List<ReportStandardMetricModel> = emptyList(),
) {
  fun validateForSubmission() {
    if (status != ReportStatus.NotSubmitted) {
      throw IllegalStateException(
          "Report $id not in Not Submitted status. Status is ${status.name}")
    }

    val incompleteStandardMetrics =
        standardMetrics.filter { it.entry.target == null || it.entry.value == null }
    if (incompleteStandardMetrics.isNotEmpty()) {
      val metricNames =
          incompleteStandardMetrics.joinToString(", ") { "(${it.metric.id}) ${it.metric.name}" }
      throw IllegalStateException(
          "Report $id is missing targets or values for standard metrics: $metricNames")
    }
  }

  companion object {
    val submittedStatuses =
        setOf(
            ReportStatus.Submitted,
            ReportStatus.Approved,
            ReportStatus.NeedsUpdate,
        )

    fun of(
        record: Record,
        standardMetricsField: Field<List<ReportStandardMetricModel>>?
    ): ReportModel {
      return with(REPORTS) {
        ReportModel(
            id = record[ID]!!,
            configId = record[CONFIG_ID]!!,
            projectId = record[PROJECT_ID]!!,
            status = record[STATUS_ID]!!,
            startDate = record[START_DATE]!!,
            endDate = record[END_DATE]!!,
            internalComment =
                if (currentUser().canReadReportInternalComments()) {
                  record[INTERNAL_COMMENT]
                } else {
                  null
                },
            feedback = record[FEEDBACK],
            createdBy = record[CREATED_BY]!!,
            createdTime = record[CREATED_TIME]!!,
            modifiedBy = record[MODIFIED_BY]!!,
            modifiedTime = record[MODIFIED_TIME]!!,
            submittedBy = record[SUBMITTED_BY],
            submittedTime = record[SUBMITTED_TIME],
            standardMetrics = standardMetricsField?.let { record[it] } ?: emptyList())
      }
    }
  }
}
