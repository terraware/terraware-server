package com.terraformation.backend.report.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.ReportsRow
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import java.time.Instant
import org.jooq.Record

/** A report consists of a fixed set of metadata and a variable-format body. */
data class ReportModel(
    val body: ReportBodyModel,
    val metadata: ReportMetadata,
) {
  val isSubmitted: Boolean
    get() = metadata.isSubmitted
}

data class ReportMetadata(
    val id: ReportId,
    val lockedBy: UserId? = null,
    val lockedTime: Instant? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val organizationId: OrganizationId,
    val quarter: Int,
    val status: ReportStatus,
    val submittedBy: UserId? = null,
    val submittedTime: Instant? = null,
    val year: Int,
) {
  constructor(
      row: ReportsRow
  ) : this(
      id = row.id!!,
      lockedBy = row.lockedBy,
      lockedTime = row.lockedTime,
      modifiedBy = row.modifiedBy,
      modifiedTime = row.modifiedTime,
      organizationId = row.organizationId!!,
      quarter = row.quarter!!,
      status = row.statusId!!,
      submittedBy = row.submittedBy,
      submittedTime = row.submittedTime,
      year = row.year!!,
  )

  constructor(
      record: Record
  ) : this(
      id = record[REPORTS.ID.asNonNullable()],
      lockedBy = record[REPORTS.LOCKED_BY],
      lockedTime = record[REPORTS.LOCKED_TIME],
      modifiedBy = record[REPORTS.MODIFIED_BY],
      modifiedTime = record[REPORTS.MODIFIED_TIME],
      organizationId = record[REPORTS.ORGANIZATION_ID.asNonNullable()],
      quarter = record[REPORTS.QUARTER.asNonNullable()],
      status = record[REPORTS.STATUS_ID.asNonNullable()],
      submittedBy = record[REPORTS.SUBMITTED_BY],
      submittedTime = record[REPORTS.SUBMITTED_TIME],
      year = record[REPORTS.YEAR.asNonNullable()],
  )

  @get:JsonIgnore
  val isSubmitted: Boolean
    get() = submittedTime != null
}

/**
 * Operations on reports that haven't been submitted yet always use the latest version. To help
 * prevent us from accidentally hardwiring specific report versions in places where the intent is
 * actually to require the latest version, define a typealias that we can update in one place when
 * we introduce new versions. This will automatically update the type signatures of all the internal
 * functions that are supposed to accept whatever the latest version is.
 */
typealias LatestReportBodyModel = ReportBodyModelV1

/**
 * Top-level interface for all versions of report bodies. Defines how versions are tagged by the
 * JSON serializer.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "version")
@JsonSubTypes(
    JsonSubTypes.Type(ReportBodyModelV1::class),
)
sealed interface ReportBodyModel {
  /** Transforms a report from an earlier version to the latest one. */
  fun toLatestVersion(): LatestReportBodyModel
}

@Suppress("unused")
enum class SustainableDevelopmentGoal {
  NoPoverty,
  ZeroHunger,
  GoodHealth,
  QualityEducation,
  GenderEquality,
  CleanWater,
  AffordableEnergy,
  DecentWork,
  Industry,
  ReducedInequalities,
  SustainableCities,
  ResponsibleConsumption,
  ClimateAction,
  LifeBelowWater,
  LifeOnLand,
  Peace,
  Partnerships
}
