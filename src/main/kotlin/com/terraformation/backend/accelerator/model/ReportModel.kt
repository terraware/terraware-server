package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_CHALLENGES
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PHOTOS
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PHOTOS
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

data class ReportPhotoModel(
    val caption: String?,
    val fileId: FileId,
) {
  companion object {
    fun of(record: Record): ReportPhotoModel {
      return ReportPhotoModel(
          caption = record[REPORT_PHOTOS.CAPTION],
          fileId = record[REPORT_PHOTOS.FILE_ID]!!,
      )
    }

    fun ofPublished(record: Record): ReportPhotoModel {
      return ReportPhotoModel(
          caption = record[PUBLISHED_REPORT_PHOTOS.CAPTION],
          fileId = record[PUBLISHED_REPORT_PHOTOS.FILE_ID]!!,
      )
    }
  }
}

data class ReportModel(
    val achievements: List<String> = emptyList(),
    val additionalComments: String? = null,
    val challenges: List<ReportChallengeModel> = emptyList(),
    val configId: ProjectReportConfigId,
    val createdBy: UserId,
    val createdByUser: SimpleUserModel,
    val createdTime: Instant,
    val endDate: LocalDate,
    val feedback: String? = null,
    val financialSummaries: String? = null,
    val frequency: ReportFrequency,
    val highlights: String? = null,
    val id: ReportId,
    val internalComment: String? = null,
    val modifiedBy: UserId,
    val modifiedByUser: SimpleUserModel,
    val modifiedTime: Instant,
    val photos: List<ReportPhotoModel> = emptyList(),
    val projectDealName: String? = null,
    val projectId: ProjectId,
    val projectMetrics: List<ReportProjectMetricModel> = emptyList(),
    val quarter: ReportQuarter?,
    val standardMetrics: List<ReportStandardMetricModel> = emptyList(),
    val startDate: LocalDate,
    val status: ReportStatus,
    val submittedBy: UserId? = null,
    val submittedByUser: SimpleUserModel? = null,
    val submittedTime: Instant? = null,
    val systemMetrics: List<ReportSystemMetricModel> = emptyList(),
) {

  /** Describes the reporting period of this report. For example "2025 Q1" or "2025 Annual" */
  val prefix: String
    get() {
      val reportYear = endDate.year
      val reportQuarter = quarter?.name ?: "Quarterly"

      return when (frequency) {
        ReportFrequency.Quarterly -> "$reportYear $reportQuarter"
        ReportFrequency.Annual -> "$reportYear Annual"
      }
    }

  fun isEditable(): Boolean {
    return status == ReportStatus.NotSubmitted || status == ReportStatus.NeedsUpdate
  }

  fun validateForSubmission() {
    if (!isEditable()) {
      throw IllegalStateException(
          "Report $id not in a submittable status. Status is ${status.name}"
      )
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

  companion object {
    val submittedStatuses =
        setOf(
            ReportStatus.Submitted,
            ReportStatus.Approved,
            ReportStatus.NeedsUpdate,
        )

    fun of(
        record: Record,
        photosField: Field<List<ReportPhotoModel>>?,
        projectMetricsField: Field<List<ReportProjectMetricModel>>?,
        standardMetricsField: Field<List<ReportStandardMetricModel>>?,
        systemMetricsField: Field<List<ReportSystemMetricModel>>?,
        achievementsField: Field<List<String>>?,
        challengesField: Field<List<ReportChallengeModel>>?,
        usersField: Field<Map<UserId, SimpleUserModel>>,
    ): ReportModel {
      val usersMap = record[usersField]!!
      val createdById = record[REPORTS.CREATED_BY]!!
      val modifiedById = record[REPORTS.MODIFIED_BY]!!
      val submittedById = record[REPORTS.SUBMITTED_BY]

      return with(REPORTS) {
        ReportModel(
            id = record[ID]!!,
            configId = record[CONFIG_ID]!!,
            projectId = record[PROJECT_ID]!!,
            projectDealName = record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME],
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
            additionalComments = record[ADDITIONAL_COMMENTS],
            financialSummaries = record[FINANCIAL_SUMMARIES],
            createdBy = createdById,
            createdByUser = usersMap[createdById]!!,
            createdTime = record[CREATED_TIME]!!,
            modifiedBy = modifiedById,
            modifiedByUser = usersMap[modifiedById]!!,
            modifiedTime = record[MODIFIED_TIME]!!,
            submittedBy = submittedById,
            submittedByUser = usersMap[submittedById],
            submittedTime = record[SUBMITTED_TIME],
            photos = photosField?.let { record[it] } ?: emptyList(),
            projectMetrics = projectMetricsField?.let { record[it] } ?: emptyList(),
            standardMetrics = standardMetricsField?.let { record[it] } ?: emptyList(),
            systemMetrics = systemMetricsField?.let { record[it] } ?: emptyList(),
        )
      }
    }
  }
}
