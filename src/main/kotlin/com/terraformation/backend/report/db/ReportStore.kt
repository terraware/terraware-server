package com.terraformation.backend.report.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.ReportAlreadySubmittedException
import com.terraformation.backend.db.ReportLockedException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.ReportNotLockedException
import com.terraformation.backend.db.ReportSubmittedException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.daos.ReportsDao
import com.terraformation.backend.db.default_schema.tables.pojos.ReportsRow
import com.terraformation.backend.db.default_schema.tables.records.ProjectReportSettingsRecord
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_REPORT_SETTINGS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_REPORT_SETTINGS
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
import com.terraformation.backend.report.model.ReportProjectSettingsModel
import com.terraformation.backend.report.model.ReportSettingsModel
import com.terraformation.backend.time.quarter
import jakarta.inject.Named
import java.time.Clock
import java.time.Month
import java.time.ZonedDateTime
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class ReportStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilitiesDao: FacilitiesDao,
    private val objectMapper: ObjectMapper,
    private val parentStore: ParentStore,
    private val projectsDao: ProjectsDao,
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

    return fetchMetadata(REPORTS.ORGANIZATION_ID.eq(organizationId))
  }

  fun fetchMetadataByProject(projectId: ProjectId): List<ReportMetadata> {
    val organizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)

    requirePermissions { listReports(organizationId) }

    return fetchMetadata(REPORTS.PROJECT_ID.eq(projectId))
  }

  private fun fetchMetadata(condition: Condition): MutableList<ReportMetadata> {
    return with(REPORTS) {
      dslContext
          .select(
              ID,
              LOCKED_BY,
              LOCKED_TIME,
              MODIFIED_BY,
              MODIFIED_TIME,
              ORGANIZATION_ID,
              PROJECT_ID,
              PROJECT_NAME,
              QUARTER,
              STATUS_ID,
              SUBMITTED_BY,
              SUBMITTED_TIME,
              YEAR,
          )
          .from(REPORTS)
          .where(condition)
          .orderBy(YEAR.desc(), QUARTER.desc())
          .fetch { ReportMetadata(it) }
    }
  }

  fun fetchSettingsByOrganization(organizationId: OrganizationId): ReportSettingsModel {
    requirePermissions { readOrganization(organizationId) }

    val organizationSettings =
        dslContext
            .select(ORGANIZATION_REPORT_SETTINGS.IS_ENABLED)
            .from(ORGANIZATION_REPORT_SETTINGS)
            .where(ORGANIZATION_REPORT_SETTINGS.ORGANIZATION_ID.eq(organizationId))
            .fetchOne()
    val projectSettings =
        dslContext
            .select(PROJECTS.ID, PROJECT_REPORT_SETTINGS.IS_ENABLED)
            .from(PROJECTS)
            .leftJoin(PROJECT_REPORT_SETTINGS)
            .on(PROJECTS.ID.eq(PROJECT_REPORT_SETTINGS.PROJECT_ID))
            .where(PROJECTS.ORGANIZATION_ID.eq(organizationId))
            .orderBy(PROJECTS.ID)
            .fetch()

    val projects =
        projectSettings.map { record ->
          val projectId = record[PROJECTS.ID.asNonNullable()]
          val isEnabled = record[PROJECT_REPORT_SETTINGS.IS_ENABLED]

          ReportProjectSettingsModel(projectId, isEnabled != null, isEnabled ?: true)
        }

    return ReportSettingsModel(
        isConfigured = organizationSettings != null,
        organizationId = organizationId,
        organizationEnabled =
            organizationSettings?.get(ORGANIZATION_REPORT_SETTINGS.IS_ENABLED) ?: true,
        projects = projects,
    )
  }

  fun lock(reportId: ReportId, force: Boolean = false) {
    requirePermissions { updateReport(reportId) }

    val userId = currentUser().userId
    val conditions =
        listOfNotNull(
            REPORTS.ID.eq(reportId),
            REPORTS.STATUS_ID.notEqual(ReportStatus.Submitted),
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
      if (isSubmitted(reportId)) {
        throw ReportSubmittedException(reportId)
      }

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
            .and(REPORTS.STATUS_ID.notEqual(ReportStatus.Submitted))
            .execute()

    if (rowsUpdated != 1) {
      if (isSubmitted(reportId)) {
        throw ReportSubmittedException(reportId)
      }

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

  /** Updates the name of a project on any project-level reports that haven't been submitted yet. */
  fun updateProjectName(projectId: ProjectId, newName: String) {
    dslContext
        .update(REPORTS)
        .set(REPORTS.PROJECT_NAME, newName)
        .where(REPORTS.PROJECT_ID.eq(projectId))
        .and(REPORTS.STATUS_ID.notEqual(ReportStatus.Submitted))
        .execute()
  }

  /**
   * Creates an empty report for the most recent quarter. This is called automatically by the
   * system, not at the request of a user.
   */
  fun create(
      organizationId: OrganizationId,
      projectId: ProjectId? = null,
      body: ReportBodyModel,
  ): ReportMetadata {
    val project =
        projectId?.let { projectsDao.fetchOneById(it) ?: throw ProjectNotFoundException(it) }
    if (project != null && project.organizationId != organizationId) {
      throw ProjectInDifferentOrganizationException()
    }

    requirePermissions { createReport(organizationId) }

    val lastQuarter = getLastQuarter()

    val row =
        ReportsRow(
            organizationId = organizationId,
            projectId = project?.id,
            projectName = project?.name,
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

    try {
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

        saveSeedBankInfo(body)
        saveNurseryInfo(body)

        eventPublisher.publishEvent(ReportSubmittedEvent(reportId, body))
      }
    } catch (e: Exception) {
      log.info("Report $reportId cannot be submitted: ${e.message}")
      throw e
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

  fun updateSettings(model: ReportSettingsModel) {
    requirePermissions { updateOrganization(model.organizationId) }

    model.projects.forEach { project ->
      requirePermissions { updateProject(project.projectId) }

      val projectOrganizationId = parentStore.getOrganizationId(project.projectId)
      if (projectOrganizationId != model.organizationId) {
        throw ProjectInDifferentOrganizationException()
      }
    }

    dslContext.transaction { _ ->
      dslContext
          .insertInto(ORGANIZATION_REPORT_SETTINGS)
          .set(ORGANIZATION_REPORT_SETTINGS.ORGANIZATION_ID, model.organizationId)
          .set(ORGANIZATION_REPORT_SETTINGS.IS_ENABLED, model.organizationEnabled)
          .onDuplicateKeyUpdate()
          .set(ORGANIZATION_REPORT_SETTINGS.IS_ENABLED, model.organizationEnabled)
          .execute()

      dslContext
          .deleteFrom(PROJECT_REPORT_SETTINGS)
          .where(
              PROJECT_REPORT_SETTINGS.PROJECT_ID.`in`(
                  DSL.select(PROJECTS.ID)
                      .from(PROJECTS)
                      .where(PROJECTS.ORGANIZATION_ID.eq(model.organizationId))))
          .execute()

      val projectRecords =
          model.projects.map { project ->
            ProjectReportSettingsRecord(
                projectId = project.projectId,
                isEnabled = project.isEnabled,
            )
          }

      dslContext.batchInsert(projectRecords).execute()
    }
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
        .andNotExists(
            DSL.selectOne()
                .from(ORGANIZATION_REPORT_SETTINGS)
                .where(
                    ORGANIZATION_REPORT_SETTINGS.ORGANIZATION_ID.eq(
                        ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID))
                .and(ORGANIZATION_REPORT_SETTINGS.IS_ENABLED.isFalse))
        .orderBy(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)
        .fetch(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.asNonNullable())
        .filter { currentUser().canCreateReport(it) }
  }

  /**
   * Returns a list of the projects whose organizations are tagged as needing to submit reports,
   * whose report settings don't say their reports are disabled, and that don't already have a
   * report for the previous quarter.
   */
  fun findProjectsForCreate(): List<ProjectId> {
    val lastQuarter = getLastQuarter()

    return dslContext
        .select(PROJECTS.ID, PROJECTS.ORGANIZATION_ID)
        .from(PROJECTS)
        .join(ORGANIZATION_INTERNAL_TAGS)
        .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID))
        .where(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(InternalTagIds.Reporter))
        .andNotExists(
            DSL.selectOne()
                .from(REPORTS)
                .where(REPORTS.PROJECT_ID.eq(PROJECTS.ID))
                .and(REPORTS.QUARTER.eq(lastQuarter.quarter))
                .and(REPORTS.YEAR.eq(lastQuarter.year)))
        .andNotExists(
            DSL.selectOne()
                .from(PROJECT_REPORT_SETTINGS)
                .where(PROJECT_REPORT_SETTINGS.PROJECT_ID.eq(PROJECTS.ID))
                .and(PROJECT_REPORT_SETTINGS.IS_ENABLED.isFalse))
        .orderBy(PROJECTS.ID)
        .fetch()
        .filter { currentUser().canCreateReport(it[PROJECTS.ORGANIZATION_ID.asNonNullable()]) }
        .map { it[PROJECTS.ID.asNonNullable()] }
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
        } ?: throw FileNotFoundException(fileId)
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
              .fetchOne() ?: throw ReportNotFoundException(reportId)

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

  /** Save the seed bank buildStartDate, buildCompletedDate, and operationStartDate */
  private fun saveSeedBankInfo(body: ReportBodyModel) {
    val reportBody = body.toLatestVersion()
    reportBody.seedBanks
        .filter { it.selected }
        .forEach {
          val seedBank = facilitiesDao.fetchOneById(it.id)
          if (it.buildStartedDate == seedBank?.buildStartedDate &&
              it.buildCompletedDate == seedBank?.buildCompletedDate &&
              it.operationStartedDate == seedBank?.operationStartedDate) {
            return
          }
          dslContext
              .update(FACILITIES)
              .set(FACILITIES.BUILD_STARTED_DATE, it.buildStartedDate ?: seedBank?.buildStartedDate)
              .set(
                  FACILITIES.BUILD_COMPLETED_DATE,
                  it.buildCompletedDate ?: seedBank?.buildCompletedDate)
              .set(
                  FACILITIES.OPERATION_STARTED_DATE,
                  it.operationStartedDate ?: seedBank?.operationStartedDate)
              .where(FACILITIES.TYPE_ID.eq(FacilityType.SeedBank))
              .and(FACILITIES.ID.eq(it.id))
              .execute()
        }
  }

  /** Save the nursery buildStartDate, buildCompletedDate, operationStartDate, and capacity */
  private fun saveNurseryInfo(body: ReportBodyModel) {
    val reportBody = body.toLatestVersion()
    reportBody.nurseries
        .filter { it.selected }
        .forEach {
          val nursery = facilitiesDao.fetchOneById(it.id)
          if (it.buildStartedDate == nursery?.buildStartedDate &&
              it.buildCompletedDate == nursery?.buildCompletedDate &&
              it.operationStartedDate == nursery?.operationStartedDate &&
              it.capacity == nursery?.capacity) {
            return
          }
          dslContext
              .update(FACILITIES)
              .set(FACILITIES.BUILD_STARTED_DATE, it.buildStartedDate ?: nursery?.buildStartedDate)
              .set(
                  FACILITIES.BUILD_COMPLETED_DATE,
                  it.buildCompletedDate ?: nursery?.buildCompletedDate)
              .set(
                  FACILITIES.OPERATION_STARTED_DATE,
                  it.operationStartedDate ?: nursery?.operationStartedDate)
              .set(FACILITIES.CAPACITY, it.capacity ?: nursery?.capacity)
              .where(FACILITIES.TYPE_ID.eq(FacilityType.Nursery))
              .and(FACILITIES.ID.eq(it.id))
              .execute()
        }
  }

  /** Returns whether a report corresponding to a given reportId has been submitted. */
  private fun isSubmitted(reportId: ReportId): Boolean {
    return dslContext
        .selectOne()
        .from(REPORTS)
        .where(REPORTS.ID.eq(reportId))
        .and(REPORTS.STATUS_ID.eq(ReportStatus.Submitted))
        .fetch()
        .isNotEmpty
  }

  /**
   * Returns a ZonedDateTime in the server's time zone for a day in the calendar quarter whose
   * reports should be available for submission.
   *
   * For Q1-Q3, this is the previous calendar quarter. However, the Q4 report becomes available on
   * December 1, not on January 1 of the following year.
   */
  fun getLastQuarter(): ZonedDateTime {
    val now = ZonedDateTime.now(clock)

    return if (now.month == Month.DECEMBER) {
      now
    } else {
      now.minusMonths(3)
    }
  }
}
