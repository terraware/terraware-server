package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
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
    val highlights: String? = null,
    val id: ReportId,
    val internalComment: String? = null,
    val modifiedBy: UserId,
    val modifiedByUser: SimpleUserModel,
    val modifiedTime: Instant,
    val photos: List<ReportPhotoModel> = emptyList(),
    val projectDealName: String? = null,
    val projectId: ProjectId,
    val projectIndicators: List<ReportProjectIndicatorModel> = emptyList(),
    val quarter: ReportQuarter?,
    val commonIndicators: List<ReportCommonIndicatorModel> = emptyList(),
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
      val reportQuarter = quarter?.name ?: "Quarterly"

      return "${endDate.year} $reportQuarter"
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
      commonIndicatorEntries: Map<CommonIndicatorId, ReportIndicatorEntryModel> = emptyMap(),
      projectIndicatorEntries: Map<ProjectIndicatorId, ReportIndicatorEntryModel> = emptyMap(),
  ) {
    val invalidProjectIndicatorIds =
        projectIndicatorEntries.keys.filter { indicatorId ->
          projectIndicators.all { it.indicator.id != indicatorId }
        }

    if (invalidProjectIndicatorIds.isNotEmpty()) {
      throw IllegalArgumentException(
          "Report $id does not contain these project indicators: " +
              invalidProjectIndicatorIds.joinToString(", "),
      )
    }

    val invalidCommonIndicatorIds =
        commonIndicatorEntries.keys.filter { indicatorId ->
          commonIndicators.all { it.indicator.id != indicatorId }
        }

    if (invalidCommonIndicatorIds.isNotEmpty()) {
      throw IllegalArgumentException(
          "Report $id does not contain these common indicators: " +
              invalidCommonIndicatorIds.joinToString(", "),
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
        projectIndicatorsField: Field<List<ReportProjectIndicatorModel>>?,
        commonIndicatorsField: Field<List<ReportCommonIndicatorModel>>?,
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
            projectIndicators = projectIndicatorsField?.let { record[it] } ?: emptyList(),
            commonIndicators = commonIndicatorsField?.let { record[it] } ?: emptyList(),
            systemMetrics = systemMetricsField?.let { record[it] } ?: emptyList(),
        )
      }
    }
  }
}
