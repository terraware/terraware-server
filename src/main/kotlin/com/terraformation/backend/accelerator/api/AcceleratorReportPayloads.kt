package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.model.CumulativeIndicatorProgressModel
import com.terraformation.backend.accelerator.model.PublishedReportComparedProps
import com.terraformation.backend.accelerator.model.ReportAutoCalculatedIndicatorModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ReportIndicatorEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.accelerator.model.ReportProjectIndicatorModel
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate

data class SimpleUserPayload(
    val userId: UserId,
    val fullName: String,
) {
  constructor(model: SimpleUserModel) : this(model.userId, model.fullName)
}

data class AcceleratorReportPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val autoCalculatedIndicators: List<ReportAutoCalculatedIndicatorPayload>,
    val challenges: List<ReportChallengePayload>,
    val commonIndicators: List<ReportCommonIndicatorPayload>,
    val endDate: LocalDate,
    val feedback: String?,
    val financialSummaries: String?,
    val highlights: String?,
    val id: ReportId,
    val internalComment: String?,
    val modifiedBy: UserId,
    val modifiedByUser: SimpleUserPayload,
    val modifiedTime: Instant,
    val photos: List<ReportPhotoPayload>,
    val projectId: ProjectId,
    val projectIndicators: List<ReportProjectIndicatorPayload>,
    val quarter: ReportQuarter?,
    val startDate: LocalDate,
    val status: ReportStatus,
    val submittedBy: UserId?,
    val submittedByUser: SimpleUserPayload?,
    val submittedTime: Instant?,
    val unpublishedProperties: List<PublishedReportComparedProps>,
) {
  constructor(
      model: ReportModel
  ) : this(
      achievements = model.achievements,
      additionalComments = model.additionalComments,
      autoCalculatedIndicators =
          model.autoCalculatedIndicators.map { ReportAutoCalculatedIndicatorPayload(it) },
      challenges = model.challenges.map { ReportChallengePayload(it) },
      commonIndicators = model.commonIndicators.map { ReportCommonIndicatorPayload(it) },
      endDate = model.endDate,
      feedback = model.feedback,
      financialSummaries = model.financialSummaries,
      highlights = model.highlights,
      id = model.id,
      internalComment = model.internalComment,
      modifiedBy = model.modifiedBy,
      modifiedByUser = SimpleUserPayload(model.modifiedByUser),
      modifiedTime = model.modifiedTime,
      photos = model.photos.map { ReportPhotoPayload(it) },
      projectId = model.projectId,
      projectIndicators = model.projectIndicators.map { ReportProjectIndicatorPayload(it) },
      quarter = model.quarter,
      startDate = model.startDate,
      status = model.status,
      submittedBy = model.submittedBy,
      submittedByUser = model.submittedByUser?.let { SimpleUserPayload(it) },
      submittedTime = model.submittedTime,
      unpublishedProperties = model.unpublishedProperties,
  )
}

data class ReportChallengePayload(
    val challenge: String,
    val mitigationPlan: String,
) {
  constructor(model: ReportChallengeModel) : this(model.challenge, model.mitigationPlan)

  fun toModel() = ReportChallengeModel(challenge = challenge, mitigationPlan = mitigationPlan)
}

data class ReportReviewPayload(
    @Schema(description = "Must be unchanged if a report has not been submitted yet.")
    val status: ReportStatus,
    val highlights: String?,
    val achievements: List<String>,
    val financialSummaries: String?,
    val additionalComments: String?,
    val challenges: List<ReportChallengePayload>,
    val feedback: String?,
    val internalComment: String?,
)

data class CumulativeIndicatorProgressPayload(
    val quarter: ReportQuarter,
    val value: BigDecimal,
) {
  constructor(
      model: CumulativeIndicatorProgressModel
  ) : this(
      quarter = model.quarter,
      value = model.value,
  )
}

data class ReportCommonIndicatorPayload(
    val baseline: BigDecimal?,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    @Schema(
        description =
            "If the indicator is cumulative, the list of actual values for all quarters in the report's year"
    )
    val currentYearProgress: List<CumulativeIndicatorProgressPayload>?,
    val description: String?,
    val endOfProjectTarget: BigDecimal?,
    val id: CommonIndicatorId,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val precision: Int,
    @Schema(
        description =
            "If the indicator is cumulative, the cumulative total at the end of the previous year"
    )
    val previousYearCumulativeTotal: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val supportingDocumentUrl: URI?,
    val target: BigDecimal?,
    val value: BigDecimal?,
) {
  constructor(
      model: ReportCommonIndicatorModel
  ) : this(
      baseline = model.baseline,
      category = model.indicator.category,
      classId = model.indicator.classId,
      currentYearProgress =
          model.currentYearProgress?.map { CumulativeIndicatorProgressPayload(it) },
      description = model.indicator.description,
      endOfProjectTarget = model.endOfProjectTarget,
      id = model.indicator.id,
      isPublishable = model.indicator.isPublishable,
      level = model.indicator.level,
      name = model.indicator.name,
      precision = model.indicator.precision,
      previousYearCumulativeTotal = model.previousYearCumulativeTotal,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      refId = model.indicator.refId,
      status = model.entry.status,
      supportingDocumentUrl = model.entry.supportingDocumentUrl,
      target = model.entry.target,
      value = model.entry.value,
  )
}

data class ReportCommonIndicatorEntriesPayload(
    val id: CommonIndicatorId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
    val supportingDocumentUrl: URI?,
    val value: BigDecimal?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          supportingDocumentUrl = supportingDocumentUrl,
          value = value,
      )
}

data class ReportAutoCalculatedIndicatorPayload(
    val baseline: BigDecimal?,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    @Schema(
        description =
            "If the indicator is cumulative, the list of actual values for all quarters in the report's year"
    )
    val currentYearProgress: List<CumulativeIndicatorProgressPayload>?,
    val description: String?,
    val endOfProjectTarget: BigDecimal?,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val indicator: AutoCalculatedIndicator,
    val overrideValue: BigDecimal?,
    val precision: Int,
    @Schema(
        description =
            "If the indicator is cumulative, the cumulative total at the end of the previous year"
    )
    val previousYearCumulativeTotal: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val supportingDocumentUrl: URI?,
    val systemTime: Instant?,
    val systemValue: BigDecimal?,
    val target: BigDecimal?,
) {
  constructor(
      model: ReportAutoCalculatedIndicatorModel
  ) : this(
      baseline = model.baseline,
      category = model.indicator.categoryId,
      classId = model.indicator.classId,
      currentYearProgress =
          model.currentYearProgress?.map { CumulativeIndicatorProgressPayload(it) },
      description = model.indicator.description,
      endOfProjectTarget = model.endOfProjectTarget,
      isPublishable = model.indicator.isPublishable,
      level = model.indicator.levelId,
      indicator = model.indicator,
      overrideValue = model.entry.overrideValue,
      precision = model.indicator.precision,
      previousYearCumulativeTotal = model.previousYearCumulativeTotal,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      refId = model.indicator.refId,
      status = model.entry.status,
      supportingDocumentUrl = model.entry.supportingDocumentUrl,
      systemTime = model.entry.systemTime,
      systemValue = model.entry.systemValue,
      target = model.entry.target,
  )
}

data class ReportAutoCalculatedIndicatorEntriesPayload(
    val indicator: AutoCalculatedIndicator,
    val overrideValue: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
    val supportingDocumentUrl: URI?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          supportingDocumentUrl = supportingDocumentUrl,
          value = overrideValue,
      )
}

data class ReportPhotoPayload(
    val caption: String?,
    val fileId: FileId,
) {
  constructor(
      model: ReportPhotoModel
  ) : this(
      caption = model.caption,
      fileId = model.fileId,
  )
}

data class ReportProjectIndicatorPayload(
    val baseline: BigDecimal?,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    @Schema(
        description =
            "If the indicator is cumulative, the list of actual values for all quarters in the report's year"
    )
    val currentYearProgress: List<CumulativeIndicatorProgressPayload>?,
    val description: String?,
    val endOfProjectTarget: BigDecimal?,
    val id: ProjectIndicatorId,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val precision: Int,
    @Schema(
        description =
            "If the indicator is cumulative, the cumulative total at the end of the previous year"
    )
    val previousYearCumulativeTotal: BigDecimal?,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val supportingDocumentUrl: URI?,
    val target: BigDecimal?,
    val unit: String?,
    val value: BigDecimal?,
) {
  constructor(
      model: ReportProjectIndicatorModel
  ) : this(
      baseline = model.baseline,
      category = model.indicator.category,
      classId = model.indicator.classId,
      currentYearProgress =
          model.currentYearProgress?.map { CumulativeIndicatorProgressPayload(it) },
      description = model.indicator.description,
      endOfProjectTarget = model.endOfProjectTarget,
      id = model.indicator.id,
      isPublishable = model.indicator.isPublishable,
      level = model.indicator.level,
      name = model.indicator.name,
      precision = model.indicator.precision,
      previousYearCumulativeTotal = model.previousYearCumulativeTotal,
      progressNotes = model.entry.progressNotes,
      projectsComments = model.entry.projectsComments,
      refId = model.indicator.refId,
      status = model.entry.status,
      supportingDocumentUrl = model.entry.supportingDocumentUrl,
      target = model.entry.target,
      unit = model.indicator.unit,
      value = model.entry.value,
  )
}

data class ReportProjectIndicatorEntriesPayload(
    val id: ProjectIndicatorId,
    val progressNotes: String?,
    val projectsComments: String?,
    val status: ReportIndicatorStatus?,
    val supportingDocumentUrl: URI?,
    val value: BigDecimal?,
) {
  fun toModel() =
      ReportIndicatorEntryModel(
          progressNotes = progressNotes,
          projectsComments = projectsComments,
          status = status,
          supportingDocumentUrl = supportingDocumentUrl,
          value = value,
      )
}

data class ReviewAcceleratorReportRequestPayload(
    val review: ReportReviewPayload,
)

data class ReviewAcceleratorReportIndicatorsRequestPayload(
    val autoCalculatedIndicators: List<ReportAutoCalculatedIndicatorEntriesPayload>,
    val commonIndicators: List<ReportCommonIndicatorEntriesPayload>,
    val projectIndicators: List<ReportProjectIndicatorEntriesPayload>,
)

data class UpdateAcceleratorReportValuesRequestPayload(
    val achievements: List<String>,
    val additionalComments: String?,
    val autoCalculatedIndicators: List<ReportAutoCalculatedIndicatorEntriesPayload>?,
    val challenges: List<ReportChallengePayload>,
    val commonIndicators: List<ReportCommonIndicatorEntriesPayload>?,
    val financialSummaries: String?,
    val highlights: String?,
    val projectIndicators: List<ReportProjectIndicatorEntriesPayload>?,
)

data class UpdateAcceleratorReportPhotoRequestPayload(val caption: String?)

data class GetAcceleratorReportResponsePayload(val report: AcceleratorReportPayload) :
    SuccessResponsePayload

data class UploadAcceleratorReportPhotoResponsePayload(val fileId: FileId) : SuccessResponsePayload
