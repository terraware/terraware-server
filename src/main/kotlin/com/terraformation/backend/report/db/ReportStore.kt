package com.terraformation.backend.report.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ReportAlreadySubmittedException
import com.terraformation.backend.db.ReportLockedException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.ReportNotLockedException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.tables.daos.ReportsDao
import com.terraformation.backend.db.default_schema.tables.pojos.ReportsRow
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.ReportService
import com.terraformation.backend.report.model.ReportBodyModel
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.JSONB

@Named
class ReportStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val objectMapper: ObjectMapper,
    private val reportsDao: ReportsDao,
) {
  private val log = perClassLogger()

  /**
   * Fetches a report in whatever format it was written to the database.
   *
   * You probably want [ReportService.fetchOneById] instead of this.
   */
  fun fetchOneById(reportId: ReportId): ReportModel {
    requirePermissions { readReport(reportId) }

    val row = reportsDao.fetchOneById(reportId) ?: throw ReportNotFoundException(reportId)
    val body = objectMapper.readValue<ReportBodyModel>(row.body!!.data())

    return ReportModel(body, ReportMetadata(row))
  }

  fun fetchMetadataByOrganization(organizationId: OrganizationId): List<ReportMetadata> {
    requirePermissions { listReports(organizationId) }

    return with(REPORTS) {
      dslContext
          .select(
              ID,
              LOCKED_BY,
              LOCKED_TIME,
              MODIFIED_BY,
              MODIFIED_TIME,
              ORGANIZATION_ID,
              QUARTER,
              STATUS_ID,
              SUBMITTED_BY,
              SUBMITTED_TIME,
              YEAR)
          .from(REPORTS)
          .where(ORGANIZATION_ID.eq(organizationId))
          .orderBy(YEAR.desc(), QUARTER.desc())
          .fetch { ReportMetadata(it) }
    }
  }

  fun lock(reportId: ReportId, force: Boolean = false) {
    requirePermissions { updateReport(reportId) }

    val userId = currentUser().userId
    val conditions =
        listOfNotNull(
            REPORTS.ID.eq(reportId),
            if (force) null else REPORTS.LOCKED_TIME.isNull.or(REPORTS.LOCKED_BY.eq(userId)),
        )

    val rowsUpdated =
        dslContext
            .update(REPORTS)
            .set(REPORTS.LOCKED_BY, userId)
            .set(REPORTS.LOCKED_TIME, clock.instant())
            .set(REPORTS.STATUS_ID, ReportStatus.Locked)
            .where(conditions)
            .execute()

    if (rowsUpdated != 1) {
      val reportExists =
          dslContext.selectOne().from(REPORTS).where(REPORTS.ID.eq(reportId)).fetch().isNotEmpty
      if (reportExists) {
        throw ReportLockedException(reportId)
      } else {
        throw ReportNotFoundException(reportId)
      }
    }
  }

  fun unlock(reportId: ReportId) {
    requirePermissions { updateReport(reportId) }

    val rowsUpdated =
        dslContext
            .update(REPORTS)
            .setNull(REPORTS.LOCKED_BY)
            .setNull(REPORTS.LOCKED_TIME)
            .set(REPORTS.STATUS_ID, ReportStatus.InProgress)
            .where(REPORTS.ID.eq(reportId))
            .and(REPORTS.LOCKED_BY.eq(currentUser().userId).or(REPORTS.LOCKED_BY.isNull))
            .execute()

    if (rowsUpdated != 1) {
      val reportExists =
          dslContext.selectOne().from(REPORTS).where(REPORTS.ID.eq(reportId)).fetch().isNotEmpty
      if (reportExists) {
        throw ReportLockedException(reportId)
      } else {
        throw ReportNotFoundException(reportId)
      }
    }
  }

  fun update(reportId: ReportId, body: ReportBodyModel) {
    requirePermissions { updateReport(reportId) }

    val json = objectMapper.writeValueAsString(body)

    val rowsUpdated =
        dslContext
            .update(REPORTS)
            .set(REPORTS.BODY, JSONB.valueOf(json))
            .set(REPORTS.MODIFIED_BY, currentUser().userId)
            .set(REPORTS.MODIFIED_TIME, clock.instant())
            .where(REPORTS.ID.eq(reportId))
            .and(REPORTS.LOCKED_BY.eq(currentUser().userId))
            .execute()

    if (rowsUpdated != 1) {
      val row = reportsDao.fetchOneById(reportId) ?: throw ReportNotFoundException(reportId)
      if (row.statusId == ReportStatus.Submitted) {
        throw ReportAlreadySubmittedException(reportId)
      } else if (row.lockedBy == null) {
        throw ReportNotLockedException(reportId)
      } else if (row.lockedBy != currentUser().userId) {
        throw ReportLockedException(reportId)
      } else {
        log.error("BUG! Failed to update report $reportId for unknown reason")
        throw RuntimeException("Failed to update report")
      }
    }
  }

  /**
   * Creates an empty report for the most recent quarter. This is called automatically by the
   * system, not at the request of a user.
   */
  fun create(organizationId: OrganizationId, body: ReportBodyModel): ReportMetadata {
    requirePermissions { createReport(organizationId) }

    // Quarter is calculated using the server's time zone.
    val now = ZonedDateTime.now(clock)

    val row =
        ReportsRow(
            organizationId = organizationId,
            quarter = (now.monthValue + 2) / 3,
            year = now.year,
            statusId = ReportStatus.New,
            body = JSONB.jsonb(objectMapper.writeValueAsString(body)),
        )

    reportsDao.insert(row)

    return ReportMetadata(row)
  }
}
