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
import com.terraformation.backend.db.SeedFundReportAlreadySubmittedException
import com.terraformation.backend.db.SeedFundReportLockedException
import com.terraformation.backend.db.SeedFundReportNotFoundException
import com.terraformation.backend.db.SeedFundReportNotLockedException
import com.terraformation.backend.db.SeedFundReportSubmittedException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportsDao
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportsRow
import com.terraformation.backend.db.default_schema.tables.records.ProjectReportSettingsRecord
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_REPORT_SETTINGS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_REPORT_SETTINGS
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORT_FILES
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORT_PHOTOS
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.SeedFundReportService
import com.terraformation.backend.report.event.SeedFundReportCreatedEvent
import com.terraformation.backend.report.event.SeedFundReportDeletionStartedEvent
import com.terraformation.backend.report.event.SeedFundReportSubmittedEvent
import com.terraformation.backend.report.model.SeedFundReportBodyModel
import com.terraformation.backend.report.model.SeedFundReportFileModel
import com.terraformation.backend.report.model.SeedFundReportMetadata
import com.terraformation.backend.report.model.SeedFundReportModel
import com.terraformation.backend.report.model.SeedFundReportPhotoModel
import com.terraformation.backend.report.model.SeedFundReportProjectSettingsModel
import com.terraformation.backend.report.model.SeedFundReportSettingsModel
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
class SeedFundReportStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilitiesDao: FacilitiesDao,
    private val objectMapper: ObjectMapper,
    private val parentStore: ParentStore,
    private val projectsDao: ProjectsDao,
    private val seedFundReportsDao: SeedFundReportsDao,
) {
  private val log = perClassLogger()

  /**
   * Fetches a report in whatever format it was written to the database.
   *
   * You probably want [SeedFundReportService.fetchOneById] instead of this.
   */
  fun fetchOneById(reportId: SeedFundReportId): SeedFundReportModel {
    requirePermissions { readSeedFundReport(reportId) }

    val row =
        seedFundReportsDao.fetchOneById(reportId) ?: throw SeedFundReportNotFoundException(reportId)
    val body = objectMapper.readValue<SeedFundReportBodyModel>(row.body!!.data())

    return SeedFundReportModel(body, SeedFundReportMetadata(row))
  }

  fun fetchMetadataByOrganization(organizationId: OrganizationId): List<SeedFundReportMetadata> {
    requirePermissions { listSeedFundReports(organizationId) }

    return fetchMetadata(SEED_FUND_REPORTS.ORGANIZATION_ID.eq(organizationId))
  }

  fun fetchMetadataByProject(projectId: ProjectId): List<SeedFundReportMetadata> {
    val organizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)

    requirePermissions { listSeedFundReports(organizationId) }

    return fetchMetadata(SEED_FUND_REPORTS.PROJECT_ID.eq(projectId))
  }

  private fun fetchMetadata(condition: Condition): MutableList<SeedFundReportMetadata> {
    return with(SEED_FUND_REPORTS) {
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
          .from(this)
          .where(condition)
          .orderBy(YEAR.desc(), QUARTER.desc())
          .fetch { SeedFundReportMetadata(it) }
    }
  }

  fun fetchSettingsByOrganization(organizationId: OrganizationId): SeedFundReportSettingsModel {
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

          SeedFundReportProjectSettingsModel(projectId, isEnabled != null, isEnabled ?: true)
        }

    return SeedFundReportSettingsModel(
        isConfigured = organizationSettings != null,
        organizationId = organizationId,
        organizationEnabled =
            organizationSettings?.get(ORGANIZATION_REPORT_SETTINGS.IS_ENABLED) ?: true,
        projects = projects,
    )
  }

  fun lock(reportId: SeedFundReportId, force: Boolean = false) {
    requirePermissions { updateSeedFundReport(reportId) }

    val userId = currentUser().userId
    val conditions =
        listOfNotNull(
            SEED_FUND_REPORTS.ID.eq(reportId),
            SEED_FUND_REPORTS.STATUS_ID.notEqual(SeedFundReportStatus.Submitted),
            if (force) null
            else SEED_FUND_REPORTS.LOCKED_TIME.isNull.or(SEED_FUND_REPORTS.LOCKED_BY.eq(userId)),
        )

    val rowsUpdated =
        dslContext
            .update(SEED_FUND_REPORTS)
            .set(SEED_FUND_REPORTS.LOCKED_BY, userId)
            .set(SEED_FUND_REPORTS.LOCKED_TIME, clock.instant())
            .set(SEED_FUND_REPORTS.STATUS_ID, SeedFundReportStatus.Locked)
            .where(conditions)
            .execute()

    if (rowsUpdated != 1) {
      if (isSubmitted(reportId)) {
        throw SeedFundReportSubmittedException(reportId)
      }

      val reportExists =
          dslContext
              .selectOne()
              .from(SEED_FUND_REPORTS)
              .where(SEED_FUND_REPORTS.ID.eq(reportId))
              .fetch()
              .isNotEmpty
      if (reportExists) {
        throw SeedFundReportLockedException(reportId)
      } else {
        throw SeedFundReportNotFoundException(reportId)
      }
    }
  }

  fun unlock(reportId: SeedFundReportId) {
    requirePermissions { updateSeedFundReport(reportId) }

    val rowsUpdated =
        dslContext
            .update(SEED_FUND_REPORTS)
            .setNull(SEED_FUND_REPORTS.LOCKED_BY)
            .setNull(SEED_FUND_REPORTS.LOCKED_TIME)
            .set(SEED_FUND_REPORTS.STATUS_ID, SeedFundReportStatus.InProgress)
            .where(SEED_FUND_REPORTS.ID.eq(reportId))
            .and(
                SEED_FUND_REPORTS.LOCKED_BY.eq(currentUser().userId)
                    .or(SEED_FUND_REPORTS.LOCKED_BY.isNull)
            )
            .and(SEED_FUND_REPORTS.STATUS_ID.notEqual(SeedFundReportStatus.Submitted))
            .execute()

    if (rowsUpdated != 1) {
      if (isSubmitted(reportId)) {
        throw SeedFundReportSubmittedException(reportId)
      }

      val reportExists =
          dslContext
              .selectOne()
              .from(SEED_FUND_REPORTS)
              .where(SEED_FUND_REPORTS.ID.eq(reportId))
              .fetch()
              .isNotEmpty
      if (reportExists) {
        throw SeedFundReportLockedException(reportId)
      } else {
        throw SeedFundReportNotFoundException(reportId)
      }
    }
  }

  fun update(reportId: SeedFundReportId, body: SeedFundReportBodyModel) {
    requirePermissions { updateSeedFundReport(reportId) }

    val json = objectMapper.writeValueAsString(body)

    ifLocked(reportId) {
      dslContext
          .update(SEED_FUND_REPORTS)
          .set(SEED_FUND_REPORTS.BODY, JSONB.valueOf(json))
          .set(SEED_FUND_REPORTS.MODIFIED_BY, currentUser().userId)
          .set(SEED_FUND_REPORTS.MODIFIED_TIME, clock.instant())
          .where(SEED_FUND_REPORTS.ID.eq(reportId))
          .execute()
    }
  }

  /** Updates the name of a project on any project-level reports that haven't been submitted yet. */
  fun updateProjectName(projectId: ProjectId, newName: String) {
    dslContext
        .update(SEED_FUND_REPORTS)
        .set(SEED_FUND_REPORTS.PROJECT_NAME, newName)
        .where(SEED_FUND_REPORTS.PROJECT_ID.eq(projectId))
        .and(SEED_FUND_REPORTS.STATUS_ID.notEqual(SeedFundReportStatus.Submitted))
        .execute()
  }

  /**
   * Creates an empty report for the most recent quarter. This is called automatically by the
   * system, not at the request of a user.
   */
  fun create(
      organizationId: OrganizationId,
      projectId: ProjectId? = null,
      body: SeedFundReportBodyModel,
  ): SeedFundReportMetadata {
    val project =
        projectId?.let { projectsDao.fetchOneById(it) ?: throw ProjectNotFoundException(it) }
    if (project != null && project.organizationId != organizationId) {
      throw ProjectInDifferentOrganizationException()
    }

    requirePermissions { createSeedFundReport(organizationId) }

    val lastQuarter = getLastQuarter()

    val row =
        SeedFundReportsRow(
            organizationId = organizationId,
            projectId = project?.id,
            projectName = project?.name,
            quarter = lastQuarter.quarter,
            year = lastQuarter.year,
            statusId = SeedFundReportStatus.New,
            body = JSONB.jsonb(objectMapper.writeValueAsString(body)),
        )

    return dslContext.transactionResult { _ ->
      seedFundReportsDao.insert(row)
      val metadata = SeedFundReportMetadata(row)

      log.info(
          "Created ${row.year}-Q${row.quarter} report ${row.id} for organization $organizationId"
      )

      eventPublisher.publishEvent(SeedFundReportCreatedEvent(metadata))

      metadata
    }
  }

  fun submit(reportId: SeedFundReportId) {
    requirePermissions { updateSeedFundReport(reportId) }

    try {
      ifLocked(reportId) {
        val body =
            dslContext
                .select(SEED_FUND_REPORTS.BODY)
                .from(SEED_FUND_REPORTS)
                .where(SEED_FUND_REPORTS.ID.eq(reportId))
                .fetchOne(SEED_FUND_REPORTS.BODY)!!
                .let { objectMapper.readValue<SeedFundReportBodyModel>(it.data()) }
                .toLatestVersion()

        body.validate()

        dslContext
            .update(SEED_FUND_REPORTS)
            .setNull(SEED_FUND_REPORTS.LOCKED_BY)
            .setNull(SEED_FUND_REPORTS.LOCKED_TIME)
            .set(SEED_FUND_REPORTS.STATUS_ID, SeedFundReportStatus.Submitted)
            .set(SEED_FUND_REPORTS.SUBMITTED_BY, currentUser().userId)
            .set(SEED_FUND_REPORTS.SUBMITTED_TIME, clock.instant())
            .where(SEED_FUND_REPORTS.ID.eq(reportId))
            .execute()

        saveSeedBankInfo(body)
        saveNurseryInfo(body)

        eventPublisher.publishEvent(SeedFundReportSubmittedEvent(reportId, body))
      }
    } catch (e: Exception) {
      log.info("Report $reportId cannot be submitted: ${e.message}")
      throw e
    }
  }

  fun delete(reportId: SeedFundReportId) {
    requirePermissions { deleteSeedFundReport(reportId) }

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
    eventPublisher.publishEvent(SeedFundReportDeletionStartedEvent(reportId))

    seedFundReportsDao.deleteById(reportId)
  }

  fun updateSettings(model: SeedFundReportSettingsModel) {
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
                      .where(PROJECTS.ORGANIZATION_ID.eq(model.organizationId))
              )
          )
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
                .from(SEED_FUND_REPORTS)
                .where(
                    SEED_FUND_REPORTS.ORGANIZATION_ID.eq(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)
                )
                .and(SEED_FUND_REPORTS.QUARTER.eq(lastQuarter.quarter))
                .and(SEED_FUND_REPORTS.YEAR.eq(lastQuarter.year))
        )
        .andNotExists(
            DSL.selectOne()
                .from(ORGANIZATION_REPORT_SETTINGS)
                .where(
                    ORGANIZATION_REPORT_SETTINGS.ORGANIZATION_ID.eq(
                        ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID
                    )
                )
                .and(ORGANIZATION_REPORT_SETTINGS.IS_ENABLED.isFalse)
        )
        .orderBy(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)
        .fetch(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.asNonNullable())
        .filter { currentUser().canCreateSeedFundReport(it) }
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
                .from(SEED_FUND_REPORTS)
                .where(SEED_FUND_REPORTS.PROJECT_ID.eq(PROJECTS.ID))
                .and(SEED_FUND_REPORTS.QUARTER.eq(lastQuarter.quarter))
                .and(SEED_FUND_REPORTS.YEAR.eq(lastQuarter.year))
        )
        .andNotExists(
            DSL.selectOne()
                .from(PROJECT_REPORT_SETTINGS)
                .where(PROJECT_REPORT_SETTINGS.PROJECT_ID.eq(PROJECTS.ID))
                .and(PROJECT_REPORT_SETTINGS.IS_ENABLED.isFalse)
        )
        .orderBy(PROJECTS.ID)
        .fetch()
        .filter {
          currentUser().canCreateSeedFundReport(it[PROJECTS.ORGANIZATION_ID.asNonNullable()])
        }
        .map { it[PROJECTS.ID.asNonNullable()] }
  }

  fun fetchFilesByReportId(reportId: SeedFundReportId): List<SeedFundReportFileModel> {
    requirePermissions { readSeedFundReport(reportId) }

    return dslContext
        .select(
            FILES.CAPTURED_LOCAL_TIME,
            FILES.CONTENT_TYPE,
            FILES.FILE_NAME,
            FILES.GEOLOCATION,
            FILES.ID,
            FILES.SIZE,
            FILES.STORAGE_URL,
        )
        .from(SEED_FUND_REPORT_FILES)
        .join(FILES)
        .on(SEED_FUND_REPORT_FILES.FILE_ID.eq(FILES.ID))
        .where(SEED_FUND_REPORT_FILES.REPORT_ID.eq(reportId))
        .orderBy(FILES.ID)
        .fetch { record ->
          SeedFundReportFileModel(
              metadata = FileMetadata.of(record),
              reportId = reportId,
          )
        }
  }

  fun fetchPhotosByReportId(reportId: SeedFundReportId): List<SeedFundReportPhotoModel> {
    requirePermissions { readSeedFundReport(reportId) }

    return dslContext
        .select(
            FILES.CAPTURED_LOCAL_TIME,
            FILES.CONTENT_TYPE,
            FILES.FILE_NAME,
            FILES.GEOLOCATION,
            FILES.ID,
            FILES.SIZE,
            FILES.STORAGE_URL,
            SEED_FUND_REPORT_PHOTOS.CAPTION,
        )
        .from(SEED_FUND_REPORT_PHOTOS)
        .join(FILES)
        .on(SEED_FUND_REPORT_PHOTOS.FILE_ID.eq(FILES.ID))
        .where(SEED_FUND_REPORT_PHOTOS.REPORT_ID.eq(reportId))
        .orderBy(FILES.ID)
        .fetch { record ->
          SeedFundReportPhotoModel(
              caption = record[SEED_FUND_REPORT_PHOTOS.CAPTION],
              metadata = FileMetadata.of(record),
              reportId = reportId,
          )
        }
  }

  fun fetchFileById(reportId: SeedFundReportId, fileId: FileId): SeedFundReportFileModel {
    requirePermissions { readSeedFundReport(reportId) }

    return dslContext
        .select(
            FILES.CAPTURED_LOCAL_TIME,
            FILES.CONTENT_TYPE,
            FILES.FILE_NAME,
            FILES.GEOLOCATION,
            FILES.ID,
            FILES.SIZE,
            FILES.STORAGE_URL,
        )
        .from(SEED_FUND_REPORT_FILES)
        .join(FILES)
        .on(SEED_FUND_REPORT_FILES.FILE_ID.eq(FILES.ID))
        .where(SEED_FUND_REPORT_FILES.REPORT_ID.eq(reportId))
        .and(SEED_FUND_REPORT_FILES.FILE_ID.eq(fileId))
        .fetchOne { record ->
          SeedFundReportFileModel(
              metadata = FileMetadata.of(record),
              reportId = reportId,
          )
        } ?: throw FileNotFoundException(fileId)
  }

  /**
   * Calls a function if the current user holds the lock on a report, or throws an appropriate
   * exception if not. The function is called in a transaction with a row lock held on the report.
   *
   * @throws SeedFundReportAlreadySubmittedException The report was already submitted.
   * @throws SeedFundReportLockedException Another user holds the lock on the report.
   * @throws SeedFundReportNotFoundException The report does not exist.
   * @throws SeedFundReportNotLockedException The report is not locked by anyone.
   */
  private fun <T> ifLocked(reportId: SeedFundReportId, func: () -> T) {
    return dslContext.transactionResult { _ ->
      val currentMetadata =
          dslContext
              .select(SEED_FUND_REPORTS.LOCKED_BY, SEED_FUND_REPORTS.STATUS_ID)
              .from(SEED_FUND_REPORTS)
              .where(SEED_FUND_REPORTS.ID.eq(reportId))
              .forUpdate()
              .fetchOne() ?: throw SeedFundReportNotFoundException(reportId)

      if (currentMetadata[SEED_FUND_REPORTS.STATUS_ID] == SeedFundReportStatus.Submitted) {
        throw SeedFundReportAlreadySubmittedException(reportId)
      } else if (currentMetadata[SEED_FUND_REPORTS.LOCKED_BY] == null) {
        throw SeedFundReportNotLockedException(reportId)
      } else if (currentMetadata[SEED_FUND_REPORTS.LOCKED_BY] != currentUser().userId) {
        throw SeedFundReportLockedException(reportId)
      }

      func()
    }
  }

  /** Save the seed bank buildStartDate, buildCompletedDate, and operationStartDate */
  private fun saveSeedBankInfo(body: SeedFundReportBodyModel) {
    val reportBody = body.toLatestVersion()
    reportBody.seedBanks
        .filter { it.selected }
        .forEach {
          val seedBank = facilitiesDao.fetchOneById(it.id)
          if (
              it.buildStartedDate == seedBank?.buildStartedDate &&
                  it.buildCompletedDate == seedBank?.buildCompletedDate &&
                  it.operationStartedDate == seedBank?.operationStartedDate
          ) {
            return
          }
          dslContext
              .update(FACILITIES)
              .set(FACILITIES.BUILD_STARTED_DATE, it.buildStartedDate ?: seedBank?.buildStartedDate)
              .set(
                  FACILITIES.BUILD_COMPLETED_DATE,
                  it.buildCompletedDate ?: seedBank?.buildCompletedDate,
              )
              .set(
                  FACILITIES.OPERATION_STARTED_DATE,
                  it.operationStartedDate ?: seedBank?.operationStartedDate,
              )
              .where(FACILITIES.TYPE_ID.eq(FacilityType.SeedBank))
              .and(FACILITIES.ID.eq(it.id))
              .execute()
        }
  }

  /** Save the nursery buildStartDate, buildCompletedDate, operationStartDate, and capacity */
  private fun saveNurseryInfo(body: SeedFundReportBodyModel) {
    val reportBody = body.toLatestVersion()
    reportBody.nurseries
        .filter { it.selected }
        .forEach {
          val nursery = facilitiesDao.fetchOneById(it.id)
          if (
              it.buildStartedDate == nursery?.buildStartedDate &&
                  it.buildCompletedDate == nursery?.buildCompletedDate &&
                  it.operationStartedDate == nursery?.operationStartedDate &&
                  it.capacity == nursery?.capacity
          ) {
            return
          }
          dslContext
              .update(FACILITIES)
              .set(FACILITIES.BUILD_STARTED_DATE, it.buildStartedDate ?: nursery?.buildStartedDate)
              .set(
                  FACILITIES.BUILD_COMPLETED_DATE,
                  it.buildCompletedDate ?: nursery?.buildCompletedDate,
              )
              .set(
                  FACILITIES.OPERATION_STARTED_DATE,
                  it.operationStartedDate ?: nursery?.operationStartedDate,
              )
              .set(FACILITIES.CAPACITY, it.capacity ?: nursery?.capacity)
              .where(FACILITIES.TYPE_ID.eq(FacilityType.Nursery))
              .and(FACILITIES.ID.eq(it.id))
              .execute()
        }
  }

  /** Returns whether a report corresponding to a given reportId has been submitted. */
  private fun isSubmitted(reportId: SeedFundReportId): Boolean {
    return dslContext
        .selectOne()
        .from(SEED_FUND_REPORTS)
        .where(SEED_FUND_REPORTS.ID.eq(reportId))
        .and(SEED_FUND_REPORTS.STATUS_ID.eq(SeedFundReportStatus.Submitted))
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
