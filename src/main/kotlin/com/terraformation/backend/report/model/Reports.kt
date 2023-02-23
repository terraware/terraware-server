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
import com.terraformation.backend.report.ReportNotCompleteException
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

  /**
   * Validates that the report is complete and ready for submission.
   *
   * @throws ReportNotCompleteException The report is missing required information.
   */
  fun validate()
}

@Suppress("unused")
enum class SustainableDevelopmentGoal(val displayName: String) {
  NoPoverty("1. No Poverty"),
  ZeroHunger("2. Zero Hunger"),
  GoodHealth("3. Good Health and Well-Being"),
  QualityEducation("4. Quality Education"),
  GenderEquality("5. Gender Equality"),
  CleanWater("6. Clean Water and Sanitation"),
  AffordableEnergy("7. Affordable and Clean Energy"),
  DecentWork("8. Decent Work and Economic Growth"),
  Industry("9. Industry, Innovation, and Infrastructure"),
  ReducedInequalities("10. Reduced Inequalities"),
  SustainableCities("11. Sustainable Cities and Communities"),
  ResponsibleConsumption("12. Responsible Consumption and Production"),
  ClimateAction("13. Climate Action"),
  LifeBelowWater("14. Life Below Water"),
  LifeOnLand("15. Life on Land"),
  Peace("16. Peace, Justice, and Strong Institutions"),
  Partnerships("17. Partnerships for the Goals")
}
