package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.tables.pojos.ReportsRow
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_CHALLENGES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class ReportChallengeModel(
    val challenge: String,
    val mitigationPlan: String,
) {
  companion object {
    fun of(record: Record): ReportChallengeModel {
      return ReportChallengeModel(
          challenge = record[REPORT_CHALLENGES.CHALLENGE]!!,
          mitigationPlan = record[REPORT_CHALLENGES.MITIGATION_PLAN]!!,
      )
    }
  }
}

data class ReportModel(
    val id: ReportId,
    val configId: ProjectReportConfigId,
    val projectId: ProjectId,
    val frequency: ReportFrequency,
    val quarter: ReportQuarter?,
    val status: ReportStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val highlights: String? = null,
    val achievements: List<String> = emptyList(),
    val challenges: List<ReportChallengeModel> = emptyList(),
    val internalComment: String? = null,
    val feedback: String? = null,
    val createdBy: UserId,
    val createdTime: Instant,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val submittedBy: UserId? = null,
    val submittedTime: Instant? = null,
    val projectMetrics: List<ReportProjectMetricModel> = emptyList(),
    val standardMetrics: List<ReportStandardMetricModel> = emptyList(),
    val systemMetrics: List<ReportSystemMetricModel> = emptyList(),
) {
  fun validateForSubmission() {
    if (status != ReportStatus.NotSubmitted) {
      throw IllegalStateException(
          "Report $id not in Not Submitted status. Status is ${status.name}")
    }

    val incompleteProjectMetrics =
        projectMetrics.filter { it.entry.target == null || it.entry.value == null }
    if (incompleteProjectMetrics.isNotEmpty()) {
      val metricNames =
          incompleteProjectMetrics.joinToString(", ") { "(${it.metric.id}) ${it.metric.name}" }
      throw IllegalStateException(
          "Report $id is missing targets or values for project metrics: $metricNames")
    }

    val incompleteStandardMetrics =
        standardMetrics.filter { it.entry.target == null || it.entry.value == null }
    if (incompleteStandardMetrics.isNotEmpty()) {
      val metricNames =
          incompleteStandardMetrics.joinToString(", ") { "(${it.metric.id}) ${it.metric.name}" }
      throw IllegalStateException(
          "Report $id is missing targets or values for standard metrics: $metricNames")
    }

    val incompleteSystemMetrics = systemMetrics.filter { it.entry.target == null }
    if (incompleteSystemMetrics.isNotEmpty()) {
      val metricNames =
          incompleteSystemMetrics.joinToString(", ") { "(${it.metric.id}) ${it.metric.name}" }
      throw IllegalStateException("Report $id is missing targets for system metrics: $metricNames")
    }
  }

  fun validateMetricEntries(
      standardMetricEntries: Map<StandardMetricId, ReportMetricEntryModel> = emptyMap(),
      projectMetricEntries: Map<ProjectMetricId, ReportMetricEntryModel> = emptyMap(),
  ) {
    val invalidProjectMetricIds =
        projectMetricEntries.keys.filter { metricId ->
          projectMetrics.all { it.metric.id != metricId }
        }

    if (invalidProjectMetricIds.isNotEmpty()) {
      throw IllegalArgumentException(
          "Report $id does not contain these project metrics: " +
              invalidProjectMetricIds.joinToString(", "),
      )
    }

    val invalidStandardMetricIds =
        standardMetricEntries.keys.filter { metricId ->
          standardMetrics.all { it.metric.id != metricId }
        }

    if (invalidStandardMetricIds.isNotEmpty()) {
      throw IllegalArgumentException(
          "Report $id does not contain these standard metrics: " +
              invalidStandardMetricIds.joinToString(", "),
      )
    }
  }

  fun toRow(): ReportsRow {
    return ReportsRow(
        id = id,
        configId = configId,
        projectId = projectId,
        reportFrequencyId = frequency,
        reportQuarterId = quarter,
        statusId = status,
        startDate = startDate,
        endDate = endDate,
        internalComment = internalComment,
        feedback = feedback,
        createdBy = createdBy,
        createdTime = createdTime,
        modifiedBy = modifiedBy,
        modifiedTime = modifiedTime,
        submittedBy = submittedBy,
        submittedTime = submittedTime,
    )
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
        projectMetricsField: Field<List<ReportProjectMetricModel>>?,
        standardMetricsField: Field<List<ReportStandardMetricModel>>?,
        systemMetricsField: Field<List<ReportSystemMetricModel>>?,
        achievementsField: Field<List<String>>?,
        challengesField: Field<List<ReportChallengeModel>>?,
    ): ReportModel {
      return with(REPORTS) {
        ReportModel(
            id = record[ID]!!,
            configId = record[CONFIG_ID]!!,
            projectId = record[PROJECT_ID]!!,
            quarter = record[REPORT_QUARTER_ID],
            frequency = record[REPORT_FREQUENCY_ID]!!,
            status = record[STATUS_ID]!!,
            startDate = record[START_DATE]!!,
            endDate = record[END_DATE]!!,
            highlights = record[HIGHLIGHTS],
            achievements = achievementsField?.let { record[it] } ?: emptyList(),
            challenges = challengesField?.let { record[it] } ?: emptyList(),
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
            projectMetrics = projectMetricsField?.let { record[it] } ?: emptyList(),
            standardMetrics = standardMetricsField?.let { record[it] } ?: emptyList(),
            systemMetrics = systemMetricsField?.let { record[it] } ?: emptyList(),
        )
      }
    }
  }
}
