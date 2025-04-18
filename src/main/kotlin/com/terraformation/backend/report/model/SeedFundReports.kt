package com.terraformation.backend.report.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportsRow
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
import com.terraformation.backend.report.SeedFundReportNotCompleteException
import java.time.Instant
import org.jooq.Record

/** A report consists of a fixed set of metadata and a variable-format body. */
data class SeedFundReportModel(
    val body: SeedFundReportBodyModel,
    val metadata: SeedFundReportMetadata,
) {
  val isSubmitted: Boolean
    get() = metadata.isSubmitted
}

data class SeedFundReportMetadata(
    val id: SeedFundReportId,
    val lockedBy: UserId? = null,
    val lockedTime: Instant? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val organizationId: OrganizationId,
    val projectId: ProjectId? = null,
    val projectName: String? = null,
    val quarter: Int,
    val status: SeedFundReportStatus,
    val submittedBy: UserId? = null,
    val submittedTime: Instant? = null,
    val year: Int,
) {
  constructor(
      row: SeedFundReportsRow
  ) : this(
      id = row.id!!,
      lockedBy = row.lockedBy,
      lockedTime = row.lockedTime,
      modifiedBy = row.modifiedBy,
      modifiedTime = row.modifiedTime,
      organizationId = row.organizationId!!,
      projectId = row.projectId,
      projectName = row.projectName,
      quarter = row.quarter!!,
      status = row.statusId!!,
      submittedBy = row.submittedBy,
      submittedTime = row.submittedTime,
      year = row.year!!,
  )

  constructor(
      record: Record
  ) : this(
      id = record[SEED_FUND_REPORTS.ID.asNonNullable()],
      lockedBy = record[SEED_FUND_REPORTS.LOCKED_BY],
      lockedTime = record[SEED_FUND_REPORTS.LOCKED_TIME],
      modifiedBy = record[SEED_FUND_REPORTS.MODIFIED_BY],
      modifiedTime = record[SEED_FUND_REPORTS.MODIFIED_TIME],
      organizationId = record[SEED_FUND_REPORTS.ORGANIZATION_ID.asNonNullable()],
      projectId = record[SEED_FUND_REPORTS.PROJECT_ID],
      projectName = record[SEED_FUND_REPORTS.PROJECT_NAME],
      quarter = record[SEED_FUND_REPORTS.QUARTER.asNonNullable()],
      status = record[SEED_FUND_REPORTS.STATUS_ID.asNonNullable()],
      submittedBy = record[SEED_FUND_REPORTS.SUBMITTED_BY],
      submittedTime = record[SEED_FUND_REPORTS.SUBMITTED_TIME],
      year = record[SEED_FUND_REPORTS.YEAR.asNonNullable()],
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
typealias LatestSeedFundReportBodyModel = SeedFundReportBodyModelV1

/**
 * Top-level interface for all versions of report bodies. Defines how versions are tagged by the
 * JSON serializer.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "version")
@JsonSubTypes(
    JsonSubTypes.Type(SeedFundReportBodyModelV1::class),
)
sealed interface SeedFundReportBodyModel {
  /** Transforms a report from an earlier version to the latest one. */
  fun toLatestVersion(): LatestSeedFundReportBodyModel

  /**
   * Validates that the report is complete and ready for submission.
   *
   * @throws SeedFundReportNotCompleteException The report is missing required information.
   */
  fun validate()
}
