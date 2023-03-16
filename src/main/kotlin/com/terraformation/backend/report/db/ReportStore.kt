package com.terraformation.backend.report.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.ReportAlreadySubmittedException
import com.terraformation.backend.db.ReportLockedException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.ReportNotLockedException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.tables.daos.ReportsDao
import com.terraformation.backend.db.default_schema.tables.pojos.ReportsRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.db.default_schema.tables.references.REPORT_FILES
import com.terraformation.backend.db.default_schema.tables.references.REPORT_PHOTOS
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.ReportService
import com.terraformation.backend.report.event.ReportCreatedEvent
import com.terraformation.backend.report.event.ReportDeletionStartedEvent
import com.terraformation.backend.report.event.ReportSubmittedEvent
import com.terraformation.backend.report.model.ReportBodyModel
import com.terraformation.backend.report.model.ReportFileModel
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import com.terraformation.backend.report.model.ReportPhotoModel
import com.terraformation.backend.time.quarter
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class ReportStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
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
              YEAR,
          )
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

    ifLocked(reportId) {
      dslContext
          .update(REPORTS)
          .set(REPORTS.BODY, JSONB.valueOf(json))
          .set(REPORTS.MODIFIED_BY, currentUser().userId)
          .set(REPORTS.MODIFIED_TIME, clock.instant())
          .where(REPORTS.ID.eq(reportId))
          .execute()
    }
  }

  /**
   * Creates an empty report for the most recent quarter. This is called automatically by the
   * system, not at the request of a user.
   */
  fun create(organizationId: OrganizationId, body: ReportBodyModel): ReportMetadata {
    requirePermissions { createReport(organizationId) }

    val lastQuarter = getLastQuarter()

    val row =
        ReportsRow(
            organizationId = organizationId,
            quarter = lastQuarter.quarter,
            year = lastQuarter.year,
            statusId = ReportStatus.New,
            body = JSONB.jsonb(objectMapper.writeValueAsString(body)),
        )

    return dslContext.transactionResult { _ ->
      reportsDao.insert(row)
      val metadata = ReportMetadata(row)

      log.info(
          "Created ${row.year}-Q${row.quarter} report ${row.id} for organization $organizationId")

      eventPublisher.publishEvent(ReportCreatedEvent(metadata))

      metadata
    }
  }

  fun submit(reportId: ReportId) {
    requirePermissions { updateReport(reportId) }

    ifLocked(reportId) {
      val body =
          dslContext
              .select(REPORTS.BODY)
              .from(REPORTS)
              .where(REPORTS.ID.eq(reportId))
              .fetchOne(REPORTS.BODY)!!
              .let { objectMapper.readValue<ReportBodyModel>(it.data()) }
              .toLatestVersion()

      body.validate()

      dslContext
          .update(REPORTS)
          .setNull(REPORTS.LOCKED_BY)
          .setNull(REPORTS.LOCKED_TIME)
          .set(REPORTS.STATUS_ID, ReportStatus.Submitted)
          .set(REPORTS.SUBMITTED_BY, currentUser().userId)
          .set(REPORTS.SUBMITTED_TIME, clock.instant())
          .where(REPORTS.ID.eq(reportId))
          .execute()

      eventPublisher.publishEvent(ReportSubmittedEvent(reportId, body))
    }
  }

  fun delete(reportId: ReportId) {
    requirePermissions { deleteReport(reportId) }

    // Inform the system that we're about to delete the report and that any external resources tied
    // to it should be cleaned up.
    //
    // This is not wrapped in a transaction because listeners are expected to delete external
    // resources and then update the database to remove the references to them; if that happened
    // inside an enclosing transaction, then a listener throwing an exception could cause the system
    // to roll back the updates that recorded the successful removal of external resources by an
    // earlier one.
    //
    // There's an unavoidable tradeoff here: if a listener fails, the report data will end up
    // partially deleted.
    eventPublisher.publishEvent(ReportDeletionStartedEvent(reportId))

    reportsDao.deleteById(reportId)
  }

  /**
   * Returns a list of the organizations that are tagged as needing to submit reports but that don't
   * already have a report for the previous quarter.
   */
  fun findOrganizationsForCreate(): List<OrganizationId> {
    val lastQuarter = getLastQuarter()

    return dslContext
        .select(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)
        .from(ORGANIZATION_INTERNAL_TAGS)
        .where(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(InternalTagIds.Reporter))
        .andNotExists(
            DSL.selectOne()
                .from(REPORTS)
                .where(REPORTS.ORGANIZATION_ID.eq(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID))
                .and(REPORTS.QUARTER.eq(lastQuarter.quarter))
                .and(REPORTS.YEAR.eq(lastQuarter.year)))
        .orderBy(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)
        .fetch(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.asNonNullable())
        .filter { currentUser().canCreateReport(it) }
  }

  fun fetchFilesByReportId(reportId: ReportId): List<ReportFileModel> {
    requirePermissions { readReport(reportId) }

    return dslContext
        .select(
            FILES.CONTENT_TYPE,
            FILES.FILE_NAME,
            FILES.ID,
            FILES.SIZE,
            FILES.STORAGE_URL,
        )
        .from(REPORT_FILES)
        .join(FILES)
        .on(REPORT_FILES.FILE_ID.eq(FILES.ID))
        .where(REPORT_FILES.REPORT_ID.eq(reportId))
        .orderBy(FILES.ID)
        .fetch { record ->
          ReportFileModel(
              metadata = FileMetadata.of(record),
              reportId = reportId,
          )
        }
  }

  fun fetchPhotosByReportId(reportId: ReportId): List<ReportPhotoModel> {
    requirePermissions { readReport(reportId) }

    return dslContext
        .select(
            FILES.CONTENT_TYPE,
            FILES.FILE_NAME,
            FILES.ID,
            FILES.SIZE,
            FILES.STORAGE_URL,
            REPORT_PHOTOS.CAPTION,
        )
        .from(REPORT_PHOTOS)
        .join(FILES)
        .on(REPORT_PHOTOS.FILE_ID.eq(FILES.ID))
        .where(REPORT_PHOTOS.REPORT_ID.eq(reportId))
        .orderBy(FILES.ID)
        .fetch { record ->
          ReportPhotoModel(
              caption = record[REPORT_PHOTOS.CAPTION],
              metadata = FileMetadata.of(record),
              reportId = reportId,
          )
        }
  }

  fun fetchFileById(reportId: ReportId, fileId: FileId): ReportFileModel {
    requirePermissions { readReport(reportId) }

    return dslContext
        .select(
            FILES.CONTENT_TYPE,
            FILES.FILE_NAME,
            FILES.ID,
            FILES.SIZE,
            FILES.STORAGE_URL,
        )
        .from(REPORT_FILES)
        .join(FILES)
        .on(REPORT_FILES.FILE_ID.eq(FILES.ID))
        .where(REPORT_FILES.REPORT_ID.eq(reportId))
        .and(REPORT_FILES.FILE_ID.eq(fileId))
        .fetchOne { record ->
          ReportFileModel(
              metadata = FileMetadata.of(record),
              reportId = reportId,
          )
        }
        ?: throw FileNotFoundException(fileId)
  }

  /**
   * Calls a function if the current user holds the lock on a report, or throws an appropriate
   * exception if not. The function is called in a transaction with a row lock held on the report.
   *
   * @throws ReportAlreadySubmittedException The report was already submitted.
   * @throws ReportLockedException Another user holds the lock on the report.
   * @throws ReportNotFoundException The report does not exist.
   * @throws ReportNotLockedException The report is not locked by anyone.
   */
  private fun <T> ifLocked(reportId: ReportId, func: () -> T) {
    return dslContext.transactionResult { _ ->
      val currentMetadata =
          dslContext
              .select(REPORTS.LOCKED_BY, REPORTS.STATUS_ID)
              .from(REPORTS)
              .where(REPORTS.ID.eq(reportId))
              .forUpdate()
              .fetchOne()
              ?: throw ReportNotFoundException(reportId)

      if (currentMetadata[REPORTS.STATUS_ID] == ReportStatus.Submitted) {
        throw ReportAlreadySubmittedException(reportId)
      } else if (currentMetadata[REPORTS.LOCKED_BY] == null) {
        throw ReportNotLockedException(reportId)
      } else if (currentMetadata[REPORTS.LOCKED_BY] != currentUser().userId) {
        throw ReportLockedException(reportId)
      }

      func()
    }
  }

  /**
   * Returns a ZonedDateTime for a day in the previous calendar quarter in the server's time zone.
   */
  private fun getLastQuarter(): ZonedDateTime = ZonedDateTime.now(clock).minusMonths(3)
}
