package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.AcceleratorReportPublishedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportSubmittedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportUpcomingEvent
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.accelerator.model.ReportProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ReportConfigNotFoundException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIdConverter
import com.terraformation.backend.db.accelerator.ReportMetricStatusConverter
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.daos.ReportsDao
import com.terraformation.backend.db.accelerator.tables.pojos.ReportsRow
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_REPORT_CONFIGS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_CHALLENGES
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PHOTOS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRIC_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRIC_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_SYSTEM_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_SYSTEM_METRIC_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.SYSTEM_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserIdConverter
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_CHALLENGES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PHOTOS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PROJECT_METRICS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_STANDARD_METRICS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_SYSTEM_METRICS
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.tracking.model.calculateSurvivalRate
import jakarta.inject.Named
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.context.ApplicationEventPublisher

/** Store class intended for accelerator-admin to configure reports and metrics. */
@Named
class ReportStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val messages: Messages,
    private val observationResultsStore:
        com.terraformation.backend.tracking.db.ObservationResultsStore,
    private val reportsDao: ReportsDao,
    private val systemUser: SystemUser,
) {
  fun fetch(
      projectId: ProjectId? = null,
      year: Int? = null,
      includeArchived: Boolean = false,
      includeFuture: Boolean = false,
      includeMetrics: Boolean = false,
  ): List<ReportModel> {
    val today = LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault())

    // By default, omits every report more than 30 days in the future.
    val futureCondition =
        if (!includeFuture) {
          REPORTS.END_DATE.lessOrEqual(today.plusDays(30))
        } else {
          null
        }

    val archivedCondition =
        if (!includeArchived) {
          REPORTS.STATUS_ID.notEqual(ReportStatus.NotNeeded)
        } else {
          null
        }

    return fetchByCondition(
        condition =
            DSL.and(
                listOfNotNull(
                    projectId?.let { REPORTS.PROJECT_ID.eq(it) },
                    year?.let { DSL.year(REPORTS.END_DATE).eq(it) },
                    futureCondition,
                    archivedCondition,
                )
            ),
        includeMetrics = includeMetrics,
    )
  }

  fun fetchOne(
      reportId: ReportId,
      includeMetrics: Boolean = false,
  ): ReportModel {
    requirePermissions { readReport(reportId) }

    return fetchByCondition(condition = REPORTS.ID.eq(reportId), includeMetrics = includeMetrics)
        .firstOrNull() ?: throw ReportNotFoundException(reportId)
  }

  fun fetchProjectReportYears(projectId: ProjectId): Pair<Int, Int>? {
    requirePermissions { readProjectReports(projectId) }

    return with(PROJECT_REPORT_CONFIGS) {
      val startYearField = DSL.year(DSL.min(REPORTING_START_DATE))
      val endYearField = DSL.year(DSL.max(REPORTING_END_DATE))

      dslContext
          .select(startYearField, endYearField)
          .from(this)
          .where(PROJECT_ID.eq(projectId))
          .fetchOne { record ->
            if (record[startYearField] != null && record[endYearField] != null) {
              record[startYearField] to record[endYearField]
            } else {
              null
            }
          }
    }
  }

  fun insertProjectReportConfig(newModel: NewProjectReportConfigModel) {
    requirePermissions { manageProjectReportConfigs() }

    dslContext.transaction { _ ->
      val config =
          with(PROJECT_REPORT_CONFIGS) {
            dslContext
                .insertInto(this)
                .set(PROJECT_ID, newModel.projectId)
                .set(REPORT_FREQUENCY_ID, newModel.frequency)
                .set(REPORTING_START_DATE, newModel.reportingStartDate)
                .set(REPORTING_END_DATE, newModel.reportingEndDate)
                .returning(ID.asNonNullable())
                .fetchOne { record ->
                  ExistingProjectReportConfigModel.of(newModel, record[ID.asNonNullable()])
                } ?: throw IllegalStateException("Failed to insert project report config.")
          }
      val reportRows = createReportRows(config)
      reportsDao.insert(reportRows)
      upsertProjectLogframeUrl(newModel.projectId, newModel.logframeUrl)
    }
  }

  fun updateProjectReportConfig(
      projectId: ProjectId,
      reportingStartDate: LocalDate,
      reportingEndDate: LocalDate,
      logframeUrl: URI?,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    dslContext.transaction { _ ->
      updateConfigDatesByCondition(
          PROJECT_REPORT_CONFIGS.PROJECT_ID.eq(projectId),
          reportingStartDate,
          reportingEndDate,
      )
      upsertProjectLogframeUrl(projectId, logframeUrl)
    }
  }

  fun updateProjectReportConfig(
      configId: ProjectReportConfigId,
      reportingStartDate: LocalDate,
      reportingEndDate: LocalDate,
      logframeUrl: URI?,
  ) {
    requirePermissions { manageProjectReportConfigs() }
    val projectId =
        dslContext
            .select(PROJECT_REPORT_CONFIGS.PROJECT_ID)
            .from(PROJECT_REPORT_CONFIGS)
            .where(PROJECT_REPORT_CONFIGS.ID.eq(configId))
            .fetchOne(PROJECT_REPORT_CONFIGS.PROJECT_ID)
            ?: throw ReportConfigNotFoundException(configId)

    dslContext.transaction { _ ->
      updateConfigDatesByCondition(
          PROJECT_REPORT_CONFIGS.ID.eq(configId),
          reportingStartDate,
          reportingEndDate,
      )
      upsertProjectLogframeUrl(projectId, logframeUrl)
    }
  }

  fun fetchProjectReportConfigs(
      projectId: ProjectId? = null
  ): List<ExistingProjectReportConfigModel> {
    requirePermissions { readProjectReportConfigs() }

    return fetchConfigsByCondition(
        projectId?.let { PROJECT_REPORT_CONFIGS.PROJECT_ID.eq(it) } ?: DSL.trueCondition()
    )
  }

  fun refreshSystemMetricValues(reportId: ReportId, metrics: Collection<SystemMetric>) {
    requirePermissions { reviewReports() }

    fetchOne(reportId)

    dslContext.transaction { _ ->
      updateReportSystemMetricWithTerrawareData(reportId, metrics)
      updateReportModifiedTime(reportId)
    }
  }

  fun reviewReport(
      reportId: ReportId,
      status: ReportStatus,
      highlights: String? = null,
      achievements: List<String>? = null,
      challenges: List<ReportChallengeModel>? = null,
      feedback: String? = null,
      internalComment: String? = null,
      financialSummaries: String? = null,
      additionalComments: String? = null,
  ) {
    requirePermissions { reviewReports() }

    val report = fetchOne(reportId)

    if (report.status != status) {
      if (report.status !in ReportModel.submittedStatuses) {
        throw IllegalStateException(
            "Cannot change the status of report $reportId because it has " +
                "not been submitted. Current status: ${report.status.name}."
        )
      }
      if (status !in ReportModel.submittedStatuses) {
        throw IllegalStateException(
            "Cannot update the status of a report to ${status.name} " +
                "because it is not a submitted status."
        )
      }
    }

    dslContext.transaction { _ ->
      achievements?.let { mergeReportAchievements(REPORT_ACHIEVEMENTS, reportId, it) }
      challenges?.let { mergeReportChallenges(REPORT_CHALLENGES, reportId, it) }

      val rowsUpdated =
          with(REPORTS) {
            dslContext
                .update(this)
                .set(ADDITIONAL_COMMENTS, additionalComments)
                .set(FEEDBACK, feedback)
                .set(FINANCIAL_SUMMARIES, financialSummaries)
                .set(HIGHLIGHTS, highlights)
                .set(INTERNAL_COMMENT, internalComment)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .set(STATUS_ID, status)
                .where(ID.eq(reportId))
                .execute()
          }

      if (rowsUpdated == 0) {
        throw IllegalStateException("Failed to update report $reportId")
      }
    }
  }

  fun reviewReportMetrics(
      reportId: ReportId,
      standardMetricEntries: Map<StandardMetricId, ReportMetricEntryModel> = emptyMap(),
      systemMetricEntries: Map<SystemMetric, ReportMetricEntryModel> = emptyMap(),
      projectMetricEntries: Map<ProjectMetricId, ReportMetricEntryModel> = emptyMap(),
  ) {
    requirePermissions { reviewReports() }

    val report = fetchOne(reportId, true)

    report.validateMetricEntries(
        standardMetricEntries = standardMetricEntries,
        projectMetricEntries = projectMetricEntries,
    )

    dslContext.transaction { _ ->
      val rowsUpdated =
          upsertReportStandardMetrics(reportId, standardMetricEntries, true) +
              upsertReportSystemMetrics(reportId, systemMetricEntries, true) +
              upsertReportProjectMetrics(reportId, projectMetricEntries, true)
      if (rowsUpdated > 0) {
        updateReportModifiedTime(reportId)
      }
    }
  }

  fun submitReport(reportId: ReportId) {
    requirePermissions { updateReport(reportId) }

    val report =
        fetchByCondition(REPORTS.ID.eq(reportId), includeMetrics = true).firstOrNull()
            ?: throw ReportNotFoundException(reportId)

    report.validateForSubmission()

    dslContext.transaction { _ ->
      val rowsUpdated =
          with(REPORTS) {
            dslContext
                .update(this)
                .set(STATUS_ID, ReportStatus.Submitted)
                .set(SUBMITTED_BY, currentUser().userId)
                .set(SUBMITTED_TIME, clock.instant())
                .where(ID.eq(reportId))
                .execute()
          }

      if (rowsUpdated < 1) {
        throw IllegalStateException("Failed to submit report $reportId")
      }

      // Update all system metrics values at submission time
      updateReportSystemMetricWithTerrawareData(reportId, SystemMetric.entries)
    }

    eventPublisher.publishEvent(AcceleratorReportSubmittedEvent(reportId))
  }

  fun updateReport(
      reportId: ReportId,
      highlights: String?,
      achievements: List<String>,
      challenges: List<ReportChallengeModel>,
      financialSummaries: String? = null,
      additionalComments: String? = null,
      standardMetricEntries: Map<StandardMetricId, ReportMetricEntryModel> = emptyMap(),
      systemMetricEntries: Map<SystemMetric, ReportMetricEntryModel> = emptyMap(),
      projectMetricEntries: Map<ProjectMetricId, ReportMetricEntryModel> = emptyMap(),
  ) {
    requirePermissions { updateReport(reportId) }

    val report = fetchOne(reportId, true)

    if (!report.isEditable()) {
      throw IllegalStateException(
          "Cannot update values for report $reportId of status ${report.status.name}"
      )
    }

    report.validateMetricEntries(
        standardMetricEntries = standardMetricEntries,
        projectMetricEntries = projectMetricEntries,
    )

    dslContext.transaction { _ ->
      mergeReportAchievements(REPORT_ACHIEVEMENTS, reportId, achievements)
      mergeReportChallenges(REPORT_CHALLENGES, reportId, challenges)

      upsertReportStandardMetrics(reportId, standardMetricEntries, false)
      upsertReportSystemMetrics(reportId, systemMetricEntries, false)
      upsertReportProjectMetrics(reportId, projectMetricEntries, false)

      dslContext
          .update(REPORTS)
          .set(REPORTS.ADDITIONAL_COMMENTS, additionalComments)
          .set(REPORTS.FINANCIAL_SUMMARIES, financialSummaries)
          .set(REPORTS.HIGHLIGHTS, highlights)
          .set(REPORTS.MODIFIED_BY, currentUser().userId)
          .set(REPORTS.MODIFIED_TIME, clock.instant())
          .where(REPORTS.ID.eq(reportId))
          .execute()
    }
  }

  fun publishReport(reportId: ReportId) {
    requirePermissions { publishReports() }

    val report = fetchOne(reportId, true)
    val now = clock.instant()
    val userId = currentUser().userId

    if (report.status != ReportStatus.Approved) {
      throw IllegalStateException(
          "Report $reportId cannot be published because the status is ${report.status.name}"
      )
    }

    dslContext.transaction { _ ->
      // Upsert the published report
      with(PUBLISHED_REPORTS) {
        dslContext
            .insertInto(this)
            .set(ADDITIONAL_COMMENTS, report.additionalComments)
            .set(END_DATE, report.endDate)
            .set(FINANCIAL_SUMMARIES, report.financialSummaries)
            .set(HIGHLIGHTS, report.highlights)
            .set(PROJECT_ID, report.projectId)
            .set(PUBLISHED_BY, userId)
            .set(PUBLISHED_TIME, now)
            .set(REPORT_FREQUENCY_ID, report.frequency)
            .set(REPORT_ID, report.id)
            .set(REPORT_QUARTER_ID, report.quarter)
            .set(START_DATE, report.startDate)
            .onConflict()
            .doUpdate()
            .set(ADDITIONAL_COMMENTS, report.additionalComments)
            .set(END_DATE, report.endDate)
            .set(FINANCIAL_SUMMARIES, report.financialSummaries)
            .set(HIGHLIGHTS, report.highlights)
            .set(PROJECT_ID, report.projectId)
            .set(PUBLISHED_BY, userId)
            .set(PUBLISHED_TIME, now)
            .set(REPORT_FREQUENCY_ID, report.frequency)
            .set(REPORT_ID, report.id)
            .set(REPORT_QUARTER_ID, report.quarter)
            .set(START_DATE, report.startDate)
            .execute()
      }

      mergeReportAchievements(PUBLISHED_REPORT_ACHIEVEMENTS, report.id, report.achievements)
      mergeReportChallenges(PUBLISHED_REPORT_CHALLENGES, report.id, report.challenges)
      publishReportPhotos(report.id, report.photos)

      val publishableSystemMetrics =
          report.systemMetrics
              .filter { it.metric.isPublishable }
              .associate { (metric, entry) ->
                metric to
                    ReportMetricEntryModel(
                        target = entry.target,
                        value = entry.overrideValue ?: entry.systemValue,
                        progressNotes = entry.progressNotes,
                        projectsComments = entry.projectsComments,
                        status = entry.status,
                    )
              }
      val publishableStandardMetrics =
          report.standardMetrics
              .filter { it.metric.isPublishable && it.entry.value != null }
              .associate { it.metric.id to it.entry }
      val publishableProjectMetrics =
          report.projectMetrics
              .filter { it.metric.isPublishable && it.entry.value != null }
              .associate { it.metric.id to it.entry }

      publishReportMetrics(
          reportId,
          PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID,
          publishableSystemMetrics,
      )
      publishReportMetrics(
          reportId,
          PUBLISHED_REPORT_STANDARD_METRICS.STANDARD_METRIC_ID,
          publishableStandardMetrics,
      )
      publishReportMetrics(
          reportId,
          PUBLISHED_REPORT_PROJECT_METRICS.PROJECT_METRIC_ID,
          publishableProjectMetrics,
      )
    }

    eventPublisher.publishEvent(AcceleratorReportPublishedEvent(reportId))
  }

  /**
   * Fetch upcoming reports and notify that they are due. Also marks the report notification time.
   */
  fun notifyUpcomingReports(): Int {
    requirePermissions { notifyUpcomingReports() }

    val today = LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault())

    return with(REPORTS) {
      dslContext.transactionResult { _ ->
        // Lock these rows to prevent multiple notifications being sent per report
        val reportIds =
            dslContext
                .select(ID)
                .from(this)
                .where(STATUS_ID.eq(ReportStatus.NotSubmitted))
                .and(UPCOMING_NOTIFICATION_SENT_TIME.isNull)
                .and(REPORT_FREQUENCY_ID.eq(ReportFrequency.Quarterly))
                .and(END_DATE.between(today).and(today.plusDays(15)))
                .forUpdate()
                .skipLocked()
                .fetch { it[ID.asNonNullable()] }

        reportIds.forEach { eventPublisher.publishEvent(AcceleratorReportUpcomingEvent(it)) }

        dslContext
            .update(this)
            .set(UPCOMING_NOTIFICATION_SENT_TIME, clock.instant())
            .where(ID.`in`(reportIds))
            .execute()
      }
    }
  }

  private fun getStartOfReportingPeriod(date: LocalDate, frequency: ReportFrequency): LocalDate {
    val startYear = date.year
    val startMonth =
        when (frequency) {
          ReportFrequency.Quarterly -> date.month.firstMonthOfQuarter()
          ReportFrequency.Annual -> Month.JANUARY
        }

    return LocalDate.of(startYear, startMonth, 1)
  }

  private fun createReportRows(config: ExistingProjectReportConfigModel): List<ReportsRow> {
    if (!config.reportingEndDate.isAfter(config.reportingStartDate)) {
      throw IllegalArgumentException("Reporting end date must be after reporting start date.")
    }

    val durationMonths =
        when (config.frequency) {
          ReportFrequency.Quarterly -> 3L
          ReportFrequency.Annual -> 12L
        }

    var startDate = getStartOfReportingPeriod(config.reportingStartDate, config.frequency)

    val now = clock.instant()

    val rows = mutableListOf<ReportsRow>()
    do {
      val reportStartDate = startDate
      startDate = startDate.plusMonths(durationMonths)
      val reportEndDate = startDate.minusDays(1)

      val quarter =
          if (config.frequency == ReportFrequency.Quarterly) {
            when (reportStartDate.month) {
              Month.JANUARY,
              Month.FEBRUARY,
              Month.MARCH -> ReportQuarter.Q1
              Month.APRIL,
              Month.MAY,
              Month.JUNE -> ReportQuarter.Q2
              Month.JULY,
              Month.AUGUST,
              Month.SEPTEMBER -> ReportQuarter.Q3
              Month.OCTOBER,
              Month.NOVEMBER,
              Month.DECEMBER -> ReportQuarter.Q4
              else -> null
            }
          } else {
            null
          }

      rows.add(
          ReportsRow(
              configId = config.id,
              projectId = config.projectId,
              reportFrequencyId = config.frequency,
              reportQuarterId = quarter,
              statusId = ReportStatus.NotSubmitted,
              startDate = reportStartDate,
              endDate = reportEndDate,
              createdBy = systemUser.userId,
              createdTime = now,
              modifiedBy = systemUser.userId,
              modifiedTime = now,
          )
      )
    } while (!startDate.isAfter(config.reportingEndDate))

    // Set the first and last report to take account of config dates
    rows[0] = rows[0].copy(startDate = config.reportingStartDate)
    rows[rows.lastIndex] = rows[rows.lastIndex].copy(endDate = config.reportingEndDate)

    return rows
  }

  private fun updateReportRows(
      config: ExistingProjectReportConfigModel,
  ) {
    val existingReports = reportsDao.fetchByConfigId(config.id).sortedBy { it.startDate }
    val newReportRows = createReportRows(config)

    if (existingReports.isEmpty()) {
      reportsDao.insert(newReportRows)
      return
    }

    if (newReportRows.isEmpty()) {
      reportsDao.update(
          existingReports.map {
            it.copy(
                statusId = ReportStatus.NotNeeded,
                modifiedBy = systemUser.userId,
                modifiedTime = clock.instant(),
                submittedBy = null,
                submittedTime = null,
            )
          }
      )
      return
    }

    // These are the types of updates that may be required
    // 1) Determine the existing reports that need to be archived
    // 2) Determine the existing reports that need to be un-archived
    // 3) Determine report rows that need to be added
    // 4) Determine reports that need dates to be updated

    val existingReportIterator = existingReports.iterator()
    val desiredReportIterator = newReportRows.iterator()

    val reportRowsToAdd = mutableListOf<ReportsRow>()
    val reportRowsToUpdate = mutableListOf<ReportsRow>()

    // Use two pointers to iterate existing reports and the desired reports
    // Both iterators should already be sorted by reporting dates
    var existingReport = existingReportIterator.next()
    var desiredReportRow = desiredReportIterator.next()

    while (true) {
      if (existingReport.endDate!!.isBefore(desiredReportRow.startDate!!)) {
        // If the existing report date is before the new report date, archive it
        if (existingReport.statusId != ReportStatus.NotNeeded) {
          reportRowsToUpdate.add(
              existingReport.copy(
                  statusId = ReportStatus.NotNeeded,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant(),
                  submittedBy = null,
                  submittedTime = null,
              )
          )
        }

        if (existingReportIterator.hasNext()) {
          existingReport = existingReportIterator.next()
        } else {
          reportRowsToAdd.add(desiredReportRow)
          break
        }
      } else if (desiredReportRow.endDate!!.isBefore(existingReport.startDate)) {
        // If the new report date is before the existing report date, this report needs to be added
        reportRowsToAdd.add(desiredReportRow)
        if (desiredReportIterator.hasNext()) {
          desiredReportRow = desiredReportIterator.next()
        } else {
          reportRowsToUpdate.add(
              existingReport.copy(
                  statusId = ReportStatus.NotNeeded,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant(),
                  submittedBy = null,
                  submittedTime = null,
              )
          )
          break
        }
      } else {
        // If the report dates overlap, they point to the same reporting period. Update the
        // existing report dates to match the desired report dates, and un-archive. Reset
        // notifications to allow for notifications again.
        if (
            existingReport.startDate != desiredReportRow.startDate!! ||
                existingReport.endDate != desiredReportRow.endDate!! ||
                existingReport.statusId == ReportStatus.NotNeeded
        ) {
          reportRowsToUpdate.add(
              existingReport.copy(
                  statusId = ReportStatus.NotSubmitted,
                  startDate = desiredReportRow.startDate!!,
                  endDate = desiredReportRow.endDate!!,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant(),
                  submittedBy = null,
                  submittedTime = null,
                  upcomingNotificationSentTime = null,
              )
          )
        }

        if (existingReportIterator.hasNext() && desiredReportIterator.hasNext()) {
          existingReport = existingReportIterator.next()
          desiredReportRow = desiredReportIterator.next()
        } else {
          break
        }
      }
    }

    // Mark any unmatched existing reports to be removed. These are past the last desired report
    reportRowsToUpdate.addAll(
        existingReportIterator
            .asSequence()
            .filter { it.statusId != ReportStatus.NotNeeded }
            .map { it.copy(statusId = ReportStatus.NotNeeded) }
    )

    // Mark any unmatched new reports to be added
    reportRowsToAdd.addAll(desiredReportIterator.asSequence())

    dslContext.transaction { _ ->
      reportsDao.insert(reportRowsToAdd)
      reportsDao.update(reportRowsToUpdate)
    }
  }

  private fun fetchByCondition(
      condition: Condition,
      includeMetrics: Boolean = false,
  ): List<ReportModel> {
    val projectMetricsField =
        if (includeMetrics) {
          projectMetricsMultiset
        } else {
          null
        }

    val standardMetricsField =
        if (includeMetrics) {
          standardMetricsMultiset
        } else {
          null
        }

    val systemMetricsField =
        if (includeMetrics) {
          systemMetricsMultiset
        } else {
          null
        }

    val usersField = reportUsersMultiset()

    val reports =
        dslContext
            .select(
                REPORTS.asterisk(),
                PROJECT_ACCELERATOR_DETAILS.DEAL_NAME,
                achievementsMultiset,
                challengesMultiset,
                photosMultiset,
                usersField,
                projectMetricsField,
                standardMetricsField,
                systemMetricsField,
            )
            .from(REPORTS)
            .leftJoin(PROJECT_ACCELERATOR_DETAILS)
            .on(REPORTS.PROJECT_ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
            .where(condition)
            .orderBy(REPORTS.START_DATE)
            .fetch {
              ReportModel.of(
                  record = it,
                  achievementsField = achievementsMultiset,
                  challengesField = challengesMultiset,
                  photosField = photosMultiset,
                  usersField = usersField,
                  projectMetricsField = projectMetricsField,
                  standardMetricsField = standardMetricsField,
                  systemMetricsField = systemMetricsField,
              )
            }
            .filter { currentUser().canReadReport(it.id) }

    // Post-process survival rate metrics
    if (includeMetrics) {
      return reports.map { report ->
        val survivalRateMetric =
            report.systemMetrics.find { it.metric == SystemMetric.SurvivalRate }
        // only calculate if systemValue is 0 from the jOOQ
        if (survivalRateMetric != null && survivalRateMetric.entry.systemTime == null) {
          val calculatedSurvivalRate = calculateSurvivalRateForReport(report.id)
          val updatedMetrics =
              report.systemMetrics.map { metric ->
                if (metric.metric == SystemMetric.SurvivalRate) {
                  metric.copy(entry = metric.entry.copy(systemValue = calculatedSurvivalRate))
                } else {
                  metric
                }
              }
          report.copy(systemMetrics = updatedMetrics)
        } else {
          report
        }
      }
    }

    return reports
  }

  private fun fetchConfigsByCondition(
      condition: Condition
  ): List<ExistingProjectReportConfigModel> {
    return with(PROJECT_REPORT_CONFIGS) {
      dslContext
          .select(asterisk(), PROJECT_ACCELERATOR_DETAILS.LOGFRAME_URL)
          .from(this)
          .leftJoin(PROJECT_ACCELERATOR_DETAILS)
          .on(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID.eq(PROJECT_ID))
          .where(condition)
          .fetch { ProjectReportConfigModel.of(it) }
    }
  }

  private fun updateConfigDatesByCondition(
      condition: Condition,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    dslContext.transaction { _ ->
      with(PROJECT_REPORT_CONFIGS) {
        dslContext
            .update(this)
            .set(REPORTING_START_DATE, startDate)
            .set(REPORTING_END_DATE, endDate)
            .where(condition)
            .execute()
      }

      val updated = fetchConfigsByCondition(condition)

      updated.forEach { updateReportRows(it) }
    }
  }

  private fun <R : Record> mergeReportAchievements(
      table: Table<R>,
      reportId: ReportId,
      achievements: List<String>,
  ) {
    val reportIdField =
        table.field("report_id", SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()))!!
    val positionField = table.field("position", Int::class.java)!!
    val achievementField = table.field("achievement", String::class.java)!!

    dslContext.transaction { _ ->
      if (achievements.isNotEmpty()) {
        var insertQuery =
            dslContext.insertInto(table, reportIdField, positionField, achievementField)

        achievements.forEachIndexed { index, achievement ->
          insertQuery = insertQuery.values(reportId, index, achievement)
        }

        insertQuery.onConflict(reportIdField, positionField).doUpdate().setAllToExcluded().execute()
      }

      dslContext
          .deleteFrom(table)
          .where(reportIdField.eq(reportId))
          .and(positionField.ge(achievements.size))
          .execute()
    }
  }

  private fun <R : Record> mergeReportChallenges(
      table: Table<R>,
      reportId: ReportId,
      challenges: List<ReportChallengeModel>,
  ) {
    val reportIdField =
        table.field("report_id", SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()))!!
    val positionField = table.field("position", Int::class.java)!!
    val challengeField = table.field("challenge", String::class.java)!!
    val mitigationPlanField = table.field("mitigation_plan", String::class.java)!!

    dslContext.transaction { _ ->
      if (challenges.isNotEmpty()) {
        var insertQuery =
            dslContext.insertInto(
                table,
                reportIdField,
                positionField,
                challengeField,
                mitigationPlanField,
            )

        challenges.forEachIndexed { index, model ->
          insertQuery = insertQuery.values(reportId, index, model.challenge, model.mitigationPlan)
        }

        insertQuery.onConflict(reportIdField, positionField).doUpdate().setAllToExcluded().execute()
      }

      dslContext
          .deleteFrom(table)
          .where(reportIdField.eq(reportId))
          .and(positionField.ge(challenges.size))
          .execute()
    }
  }

  private fun upsertProjectLogframeUrl(
      projectId: ProjectId,
      logframeUrl: URI?,
  ) {
    with(PROJECT_ACCELERATOR_DETAILS) {
      dslContext
          .insertInto(this)
          .set(PROJECT_ID, projectId)
          .set(LOGFRAME_URL, logframeUrl)
          .onConflict(PROJECT_ID)
          .doUpdate()
          .set(LOGFRAME_URL, logframeUrl)
          .execute()
    }
  }

  private fun <ID : Any> upsertReportMetrics(
      reportId: ReportId,
      metricIdField: TableField<*, ID?>,
      entries: Map<ID, ReportMetricEntryModel>,
      updateProgressNotes: Boolean,
  ): Int {
    if (entries.isEmpty()) {
      return 0
    }

    val table = metricIdField.table!!
    val reportIdField =
        table.field("report_id", SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()))!!
    val valueField = table.field("value", Int::class.java)!!
    val projectsCommentsField = table.field("projects_comments", String::class.java)!!
    val progressNotesField = table.field("progress_notes", String::class.java)
    val statusField =
        table.field(
            "status_id",
            SQLDataType.INTEGER.asConvertedDataType(ReportMetricStatusConverter()),
        )!!
    val modifiedByField =
        table.field("modified_by", SQLDataType.BIGINT.asConvertedDataType(UserIdConverter()))
    val modifiedTimeField = table.field("modified_time", Instant::class.java)

    var insertQuery = dslContext.insertInto(table).set()

    val iterator = entries.iterator()

    while (iterator.hasNext()) {
      val (metricId, entry) = iterator.next()
      insertQuery =
          insertQuery
              .set(reportIdField, reportId)
              .set(metricIdField, metricId)
              .set(valueField, entry.value)
              .set(projectsCommentsField, entry.projectsComments)
              .set(statusField, entry.status)
              .apply {
                if (modifiedByField != null) {
                  this.set(modifiedByField, currentUser().userId)
                }
              }
              .apply {
                if (modifiedTimeField != null) {
                  this.set(modifiedTimeField, clock.instant())
                }
              }
              .apply {
                if (updateProgressNotes && progressNotesField != null) {
                  this.set(progressNotesField, entry.progressNotes)
                }
              }
              .apply {
                if (iterator.hasNext()) {
                  this.newRecord()
                }
              }
    }

    val rowsUpdated =
        insertQuery.onConflict(reportIdField, metricIdField).doUpdate().setAllToExcluded().execute()

    return rowsUpdated
  }

  private fun <ID : Any> upsertReportMetricTargets(
      metricIdField: TableField<*, ID?>,
      metricId: ID,
      targets: Map<ReportId, Int?>,
  ): Int {
    if (targets.isEmpty()) {
      return 0
    }

    val table = metricIdField.table!!
    val reportIdField =
        table.field("report_id", SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()))!!
    val targetField = table.field("target", Int::class.java)!!
    val modifiedByField =
        table.field("modified_by", SQLDataType.BIGINT.asConvertedDataType(UserIdConverter()))
    val modifiedTimeField = table.field("modified_time", Instant::class.java)

    var insertQuery = dslContext.insertInto(table).set()

    val iterator = targets.iterator()

    while (iterator.hasNext()) {
      val (reportId, target) = iterator.next()
      insertQuery =
          insertQuery
              .set(reportIdField, reportId)
              .set(metricIdField, metricId)
              .set(targetField, target)
              .set(modifiedByField, currentUser().userId)
              .set(modifiedTimeField, clock.instant())
              .apply {
                if (iterator.hasNext()) {
                  this.newRecord()
                }
              }
    }

    val rowsUpdated =
        insertQuery.onConflict(reportIdField, metricIdField).doUpdate().setAllToExcluded().execute()

    return rowsUpdated
  }

  private fun <ID : Any> publishReportMetrics(
      reportId: ReportId,
      metricIdField: TableField<*, ID?>,
      entries: Map<ID, ReportMetricEntryModel>,
  ) {
    val table = metricIdField.table!!
    val reportIdField =
        table.field("report_id", SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()))!!

    dslContext.transaction { _ ->
      // Insert all entries
      upsertReportMetrics(reportId, metricIdField, entries, true)

      // Delete any entries that are not publishable
      dslContext
          .deleteFrom(table)
          .where(reportIdField.eq(reportId))
          .and(metricIdField.notIn(entries.keys))
          .execute()
    }
  }

  private fun publishReportPhotos(
      reportId: ReportId,
      photos: List<ReportPhotoModel>,
  ) {
    if (photos.isNotEmpty()) {
      var insertQuery =
          dslContext.insertInto(
              PUBLISHED_REPORT_PHOTOS,
              PUBLISHED_REPORT_PHOTOS.REPORT_ID,
              PUBLISHED_REPORT_PHOTOS.FILE_ID,
              PUBLISHED_REPORT_PHOTOS.CAPTION,
          )

      photos.forEach { photo ->
        insertQuery = insertQuery.values(reportId, photo.fileId, photo.caption)
      }

      insertQuery.onConflict().doUpdate().setAllToExcluded().execute()
    }
  }

  private fun updateReportSystemMetricWithTerrawareData(
      reportId: ReportId,
      metrics: Collection<SystemMetric>,
  ): Int {
    if (metrics.isEmpty()) {
      return 0
    }

    val survivalRate =
        if (metrics.contains(SystemMetric.SurvivalRate)) {
          calculateSurvivalRateForReport(reportId)
        } else {
          null
        }

    // If the report is not submitted, set the system value to null.
    val systemValueField =
        DSL.`when`(
                REPORTS.STATUS_ID.`in`(ReportModel.submittedStatuses),
                DSL.`when`(SYSTEM_METRICS.ID.eq(SystemMetric.SurvivalRate), survivalRate)
                    .else_(systemTerrawareValueField),
            )
            .else_(DSL.value(null, REPORT_SYSTEM_METRICS.SYSTEM_VALUE))
    val systemTimeField =
        DSL.`when`(
                REPORTS.STATUS_ID.`in`(ReportModel.submittedStatuses),
                DSL.value(clock.instant()),
            )
            .else_(DSL.value(null, REPORT_SYSTEM_METRICS.SYSTEM_TIME))

    return with(REPORT_SYSTEM_METRICS) {
      dslContext
          .insertInto(
              this,
              REPORT_ID,
              SYSTEM_METRIC_ID,
              SYSTEM_VALUE,
              SYSTEM_TIME,
              OVERRIDE_VALUE,
              MODIFIED_BY,
              MODIFIED_TIME,
          )
          .select(
              DSL.select(
                      REPORTS.ID,
                      SYSTEM_METRICS.ID,
                      systemValueField,
                      systemTimeField,
                      DSL.value(null, OVERRIDE_VALUE),
                      DSL.value(currentUser().userId),
                      DSL.value(clock.instant()),
                  )
                  .from(SYSTEM_METRICS)
                  .join(REPORTS)
                  .on(REPORTS.ID.eq(reportId))
                  .where(SYSTEM_METRICS.ID.`in`(metrics))
          )
          .onConflict(REPORT_ID, SYSTEM_METRIC_ID)
          .doUpdate()
          .setAllToExcluded()
          .execute()
    }
  }

  private fun upsertReportStandardMetrics(
      reportId: ReportId,
      entries: Map<StandardMetricId, ReportMetricEntryModel>,
      updateProgressNotes: Boolean,
  ) =
      upsertReportMetrics(
          reportId = reportId,
          metricIdField = REPORT_STANDARD_METRICS.STANDARD_METRIC_ID,
          entries = entries,
          updateProgressNotes = updateProgressNotes,
      )

  private fun upsertReportSystemMetrics(
      reportId: ReportId,
      entries: Map<SystemMetric, ReportMetricEntryModel>,
      updateProgressNotes: Boolean,
  ): Int {
    if (entries.isEmpty()) {
      return 0
    }

    var insertQuery = dslContext.insertInto(REPORT_SYSTEM_METRICS).set()

    val iterator = entries.iterator()

    while (iterator.hasNext()) {
      val (metricId, entry) = iterator.next()
      insertQuery =
          insertQuery
              .set(REPORT_SYSTEM_METRICS.REPORT_ID, reportId)
              .set(REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID, metricId)
              .set(
                  REPORT_SYSTEM_METRICS.PROJECTS_COMMENTS,
                  entry.projectsComments,
              )
              .set(REPORT_SYSTEM_METRICS.STATUS_ID, entry.status)
              .set(REPORT_SYSTEM_METRICS.MODIFIED_BY, currentUser().userId)
              .set(REPORT_SYSTEM_METRICS.MODIFIED_TIME, clock.instant())
              .apply {
                if (updateProgressNotes) {
                  this.set(REPORT_SYSTEM_METRICS.OVERRIDE_VALUE, entry.value)
                  this.set(REPORT_SYSTEM_METRICS.PROGRESS_NOTES, entry.progressNotes)
                }
              }
              .apply {
                if (iterator.hasNext()) {
                  this.newRecord()
                }
              }
    }

    val rowsUpdated =
        insertQuery
            .onConflict(REPORT_SYSTEM_METRICS.REPORT_ID, REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID)
            .doUpdate()
            .setAllToExcluded()
            .execute()

    return rowsUpdated
  }

  private fun upsertReportProjectMetrics(
      reportId: ReportId,
      entries: Map<ProjectMetricId, ReportMetricEntryModel>,
      updateProgressNotes: Boolean,
  ) =
      upsertReportMetrics(
          reportId = reportId,
          metricIdField = REPORT_PROJECT_METRICS.PROJECT_METRIC_ID,
          entries = entries,
          updateProgressNotes = updateProgressNotes,
      )

  private fun updateReportModifiedTime(reportId: ReportId) {
    dslContext
        .update(REPORTS)
        .set(REPORTS.MODIFIED_BY, currentUser().userId)
        .set(REPORTS.MODIFIED_TIME, clock.instant())
        .where(REPORTS.ID.eq(reportId))
        .execute()
  }

  private val achievementsMultiset: Field<List<String>> =
      DSL.multiset(
              DSL.select(REPORT_ACHIEVEMENTS.ACHIEVEMENT)
                  .from(REPORT_ACHIEVEMENTS)
                  .where(REPORT_ACHIEVEMENTS.REPORT_ID.eq(REPORTS.ID))
                  .orderBy(REPORT_ACHIEVEMENTS.POSITION)
          )
          .convertFrom { results ->
            results.map { it[REPORT_ACHIEVEMENTS.ACHIEVEMENT.asNonNullable()] }
          }

  private val challengesMultiset: Field<List<ReportChallengeModel>> =
      DSL.multiset(
              DSL.select(REPORT_CHALLENGES.CHALLENGE, REPORT_CHALLENGES.MITIGATION_PLAN)
                  .from(REPORT_CHALLENGES)
                  .where(REPORT_CHALLENGES.REPORT_ID.eq(REPORTS.ID))
                  .orderBy(REPORT_CHALLENGES.POSITION)
          )
          .convertFrom { results -> results.map { ReportChallengeModel.of(it) } }

  private val standardMetricsMultiset: Field<List<ReportStandardMetricModel>> =
      DSL.multiset(
              DSL.select(
                      STANDARD_METRICS.asterisk(),
                      REPORT_STANDARD_METRICS.asterisk(),
                      REPORT_STANDARD_METRIC_TARGETS.TARGET,
                  )
                  .from(STANDARD_METRICS)
                  .leftJoin(REPORT_STANDARD_METRICS)
                  .on(STANDARD_METRICS.ID.eq(REPORT_STANDARD_METRICS.STANDARD_METRIC_ID))
                  .and(REPORTS.ID.eq(REPORT_STANDARD_METRICS.REPORT_ID))
                  .leftJoin(REPORT_STANDARD_METRIC_TARGETS)
                  .on(REPORT_STANDARD_METRIC_TARGETS.STANDARD_METRIC_ID.eq(STANDARD_METRICS.ID))
                  .and(REPORT_STANDARD_METRIC_TARGETS.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                  .and(REPORT_STANDARD_METRIC_TARGETS.YEAR.eq(DSL.year(REPORTS.END_DATE)))
                  .orderBy(STANDARD_METRICS.REFERENCE, STANDARD_METRICS.ID)
          )
          .convertFrom { results -> results.map { ReportStandardMetricModel.of(it) } }

  private val photosMultiset: Field<List<ReportPhotoModel>> =
      DSL.multiset(
              DSL.select(
                      REPORT_PHOTOS.CAPTION,
                      REPORT_PHOTOS.FILE_ID,
                  )
                  .from(REPORT_PHOTOS)
                  .where(REPORT_PHOTOS.REPORT_ID.eq(REPORTS.ID))
                  .and(REPORT_PHOTOS.DELETED.isFalse)
                  .orderBy(REPORT_PHOTOS.FILE_ID)
          )
          .convertFrom { results -> results.map { ReportPhotoModel.of(it) } }

  private val projectMetricsMultiset: Field<List<ReportProjectMetricModel>> =
      DSL.multiset(
              DSL.select(
                      PROJECT_METRICS.asterisk(),
                      REPORT_PROJECT_METRICS.asterisk(),
                      REPORT_PROJECT_METRIC_TARGETS.TARGET,
                  )
                  .from(PROJECT_METRICS)
                  .leftJoin(REPORT_PROJECT_METRICS)
                  .on(PROJECT_METRICS.ID.eq(REPORT_PROJECT_METRICS.PROJECT_METRIC_ID))
                  .and(REPORTS.ID.eq(REPORT_PROJECT_METRICS.REPORT_ID))
                  .leftJoin(REPORT_PROJECT_METRIC_TARGETS)
                  .on(REPORT_PROJECT_METRIC_TARGETS.PROJECT_METRIC_ID.eq(PROJECT_METRICS.ID))
                  .and(REPORT_PROJECT_METRIC_TARGETS.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                  .and(REPORT_PROJECT_METRIC_TARGETS.YEAR.eq(DSL.year(REPORTS.END_DATE)))
                  .where(PROJECT_METRICS.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                  .orderBy(PROJECT_METRICS.REFERENCE, PROJECT_METRICS.ID)
          )
          .convertFrom { results -> results.map { ReportProjectMetricModel.of(it) } }

  private fun userIsInSameOrg() =
      with(ORGANIZATION_USERS) {
        DSL.field(
            if (currentUser() is SystemUser) {
              DSL.falseCondition()
            } else {
              DSL.exists(
                  DSL.selectOne()
                      .from(ORGANIZATION_USERS)
                      .where(USER_ID.eq(USERS.ID))
                      .and(ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
              )
            }
        )
      }

  private fun reportUsersMultiset(): Field<Map<UserId, SimpleUserModel>> {
    val sameOrgField = userIsInSameOrg()
    return with(USERS) {
      DSL.multiset(
              DSL.selectDistinct(
                      ID,
                      FIRST_NAME,
                      LAST_NAME,
                      EMAIL,
                      sameOrgField,
                      DELETED_TIME,
                  )
                  .from(USERS)
                  .where(ID.`in`(REPORTS.CREATED_BY, REPORTS.MODIFIED_BY, REPORTS.SUBMITTED_BY))
          )
          .convertFrom { results ->
            results
                .map {
                  SimpleUserModel.create(
                      it[ID.asNonNullable()],
                      it[FIRST_NAME],
                      it[LAST_NAME],
                      it[EMAIL.asNonNullable()],
                      it[sameOrgField],
                      it[DELETED_TIME] != null,
                      messages,
                  )
                }
                .associateBy { it.userId }
          }
    }
  }

  private fun timestampToLocalDateField(
      timestampField: Field<Instant?>,
      timezoneField: Field<ZoneId>,
  ): Field<LocalDate> {
    // https://github.com/jOOQ/jOOQ/issues/7238
    return DSL.field(
        "({0}) AT TIME ZONE ({1})",
        LocalDate::class.java,
        timestampField,
        timezoneField,
    )
  }

  private fun validateReportsInProject(reportIds: Set<ReportId>, projectId: ProjectId) {
    val invalidReportIds =
        dslContext
            .select(REPORTS.ID)
            .from(REPORTS)
            .where(REPORTS.ID.`in`(reportIds))
            .and(REPORTS.PROJECT_ID.notEqual(projectId))
            .fetch { it[REPORTS.ID]!! }
            .toSet()

    if (!invalidReportIds.isEmpty()) {
      throw IllegalStateException(
          "Reports ${invalidReportIds.joinToString()} are not in project $projectId."
      )
    }
  }

  private fun validateReportsUpdatable(reportIds: Set<ReportId>) {
    val invalidReportIds =
        dslContext
            .select(REPORTS.ID)
            .from(REPORTS)
            .where(REPORTS.ID.`in`(reportIds))
            .and(REPORTS.STATUS_ID.notIn(ReportStatus.NotSubmitted, ReportStatus.NeedsUpdate))
            .fetch { it[REPORTS.ID]!! }
            .toSet()

    if (!invalidReportIds.isEmpty()) {
      throw IllegalStateException("Reports ${invalidReportIds.joinToString()} are not updatable")
    }
  }

  // Timezone for a planting site. Defaults to planting site, then organization, then UTC
  private fun plantingSiteTimeZoneField(
      plantingSiteIdField: Field<PlantingSiteId?>
  ): Field<ZoneId> {
    return DSL.field(
            DSL.select(
                    DSL.coalesce(
                        PLANTING_SITES.TIME_ZONE,
                        ORGANIZATIONS.TIME_ZONE,
                        DSL.value("UTC"),
                    )
                )
                .from(PLANTING_SITES)
                .join(ORGANIZATIONS)
                .on(PLANTING_SITES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                .where(PLANTING_SITES.ID.eq(plantingSiteIdField))
        )
        .asNonNullable()
  }

  private val hectaresPlantedField =
      with(SUBSTRATA) {
        DSL.field(
                DSL.select(DSL.sum(AREA_HA))
                    .from(this)
                    .join(PLANTING_SITES)
                    .on(PLANTING_SITES.ID.eq(PLANTING_SITE_ID))
                    .where(
                        timestampToLocalDateField(
                                PLANTING_COMPLETED_TIME,
                                plantingSiteTimeZoneField(PLANTING_SITE_ID),
                            )
                            .le(REPORTS.END_DATE)
                    )
                    .and(PLANTING_SITES.PROJECT_ID.eq(REPORTS.PROJECT_ID))
            )
            .convertFrom { it.toInt() }
      }

  private val observationsInReportPeriod =
      DSL.select(OBSERVATIONS.ID)
          .distinctOn(OBSERVATIONS.PLANTING_SITE_ID)
          .from(OBSERVATIONS)
          .where(
              timestampToLocalDateField(
                      OBSERVATIONS.COMPLETED_TIME,
                      plantingSiteTimeZoneField(OBSERVATIONS.PLANTING_SITE_ID),
                  )
                  .le(REPORTS.END_DATE)
          )
          .and(OBSERVATIONS.plantingSites.PROJECT_ID.eq(REPORTS.PROJECT_ID))
          .and(OBSERVATIONS.IS_AD_HOC.isFalse)
          .orderBy(
              OBSERVATIONS.PLANTING_SITE_ID,
              OBSERVATIONS.COMPLETED_TIME.desc(),
          )

  // Calculate survival rate by fetching observations from observationResultsStore
  private fun calculateSurvivalRateForReport(reportId: ReportId): Int? {
    // retrieve the latest observation for each planting site in the report's project within the
    // report period. observationsInReportPeriod only returns one observation per planting site.
    val observationIds =
        dslContext
            .select(OBSERVATIONS.ID)
            .from(OBSERVATIONS, REPORTS)
            .where(REPORTS.ID.eq(reportId))
            .and(OBSERVATIONS.ID.`in`(observationsInReportPeriod))
            .fetch { it[OBSERVATIONS.ID]!! }

    if (observationIds.isEmpty()) {
      return null
    }

    val observationResults = observationIds.mapNotNull { observationResultsStore.fetchOneById(it) }

    val speciesPairs =
        observationResults
            .filter { it.survivalRate != null }
            .map { it.species to it.survivalRateIncludesTempPlots }

    if (speciesPairs.isEmpty()) {
      return null
    }

    // Calculate sumDensity and numKnownLive across all observations
    var sumDensity = BigDecimal.ZERO
    var numKnownLive = 0

    speciesPairs.forEach { (speciesList, includesTempPlots) ->
      sumDensity += speciesList.mapNotNull { it.t0Density }.sumOf { it }
      numKnownLive +=
          if (includesTempPlots) {
            speciesList.sumOf { it.latestLive }
          } else {
            speciesList.sumOf { it.permanentLive }
          }
    }

    return calculateSurvivalRate(numKnownLive, sumDensity)
  }

  private val seedsCollectedField =
      with(ACCESSIONS) {
        DSL.field(
                DSL.select(DSL.sum(EST_SEED_COUNT) + DSL.sum(TOTAL_WITHDRAWN_COUNT))
                    .from(this)
                    .where(PROJECT_ID.eq(REPORTS.PROJECT_ID))
                    .and(COLLECTED_DATE.between(REPORTS.START_DATE, REPORTS.END_DATE))
            )
            .convertFrom { it?.toInt() }
      }

  private val withdrawnSeedlingsField =
      with(BATCH_WITHDRAWALS) {
        DSL.field(
            DSL.select(
                    DSL.sum(READY_QUANTITY_WITHDRAWN) +
                        DSL.sum(GERMINATING_QUANTITY_WITHDRAWN) +
                        DSL.sum(ACTIVE_GROWTH_QUANTITY_WITHDRAWN)
                )
                .from(this)
                .join(WITHDRAWAL_SUMMARIES)
                .on(WITHDRAWAL_SUMMARIES.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
                .where(BATCH_ID.eq(BATCHES.ID))
                .and(WITHDRAWAL_SUMMARIES.PURPOSE_ID.notEqual(WithdrawalPurpose.NurseryTransfer))
                .and(WITHDRAWAL_SUMMARIES.PURPOSE_ID.notEqual(WithdrawalPurpose.Undo))
                .and(WITHDRAWAL_SUMMARIES.UNDONE_BY_WITHDRAWAL_ID.isNull)
        )
      }

  private val seedlingsField =
      with(BATCHES) {
        DSL.field(
                DSL.select(
                        DSL.sum(READY_QUANTITY) +
                            DSL.sum(GERMINATING_QUANTITY) +
                            DSL.sum(ACTIVE_GROWTH_QUANTITY) +
                            DSL.coalesce(DSL.sum(withdrawnSeedlingsField), 0)
                    )
                    .from(this)
                    .where(PROJECT_ID.eq(REPORTS.PROJECT_ID))
                    .and(ADDED_DATE.between(REPORTS.START_DATE, REPORTS.END_DATE))
            )
            .convertFrom { it?.toInt() }
      }

  // For species, we total up the number of trees planted per species, and take only ones that are
  // greater than zero, to correctly take account of "Undone" plantings.
  private val speciesPlantedField =
      with(PLANTINGS) {
        DSL.field(
            DSL.select(DSL.count())
                .from(
                    DSL.select(SPECIES_ID)
                        .from(this)
                        .join(DELIVERIES)
                        .on(DELIVERIES.ID.eq(DELIVERY_ID))
                        .join(WITHDRAWAL_SUMMARIES)
                        .on(WITHDRAWAL_SUMMARIES.ID.eq(DELIVERIES.WITHDRAWAL_ID))
                        .join(PLANTING_SITES)
                        .on(PLANTING_SITES.ID.eq(PLANTING_SITE_ID))
                        .where(
                            WITHDRAWAL_SUMMARIES.WITHDRAWN_DATE.between(
                                REPORTS.START_DATE,
                                REPORTS.END_DATE,
                            )
                        )
                        .and(PLANTING_SITES.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                        .and(WITHDRAWAL_SUMMARIES.PURPOSE_ID.notEqual(WithdrawalPurpose.Undo))
                        .and(WITHDRAWAL_SUMMARIES.UNDONE_BY_WITHDRAWAL_ID.isNull)
                        .groupBy(SPECIES_ID)
                        .having(DSL.sum(NUM_PLANTS).ge(BigDecimal.ZERO))
                )
        )
      }

  private val treesPlantedField =
      with(PLANTINGS) {
        DSL.field(
                DSL.select(DSL.sum(NUM_PLANTS))
                    .from(this)
                    .join(DELIVERIES)
                    .on(DELIVERIES.ID.eq(DELIVERY_ID))
                    .join(WITHDRAWAL_SUMMARIES)
                    .on(WITHDRAWAL_SUMMARIES.ID.eq(DELIVERIES.WITHDRAWAL_ID))
                    .join(PLANTING_SITES)
                    .on(PLANTING_SITES.ID.eq(PLANTING_SITE_ID))
                    .where(
                        WITHDRAWAL_SUMMARIES.WITHDRAWN_DATE.between(
                            REPORTS.START_DATE,
                            REPORTS.END_DATE,
                        )
                    )
                    .and(PLANTING_SITES.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                    .and(WITHDRAWAL_SUMMARIES.PURPOSE_ID.notEqual(WithdrawalPurpose.Undo))
                    .and(WITHDRAWAL_SUMMARIES.UNDONE_BY_WITHDRAWAL_ID.isNull)
            )
            .convertFrom { it?.toInt() }
      }

  private val systemTerrawareValueField =
      DSL.coalesce(
          DSL.case_()
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.Seedlings), seedlingsField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.SeedsCollected), seedsCollectedField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.SpeciesPlanted), speciesPlantedField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.TreesPlanted), treesPlantedField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.HectaresPlanted), hectaresPlantedField)
              .`when`(
                  SYSTEM_METRICS.ID.eq(SystemMetric.SurvivalRate),
                  DSL.value(null, REPORT_SYSTEM_METRICS.SYSTEM_VALUE),
              )
              .else_(0),
          DSL.value(0),
      )

  private val systemValueField =
      DSL.case_()
          .`when`(REPORT_SYSTEM_METRICS.SYSTEM_TIME.isNull(), systemTerrawareValueField)
          .else_(REPORT_SYSTEM_METRICS.SYSTEM_VALUE)

  private val systemMetricsMultiset: Field<List<ReportSystemMetricModel>> =
      DSL.multiset(
              DSL.select(
                      SYSTEM_METRICS.ID,
                      REPORT_SYSTEM_METRICS.asterisk(),
                      systemValueField,
                      REPORT_SYSTEM_METRIC_TARGETS.TARGET,
                  )
                  .from(SYSTEM_METRICS)
                  .leftJoin(REPORT_SYSTEM_METRICS)
                  .on(SYSTEM_METRICS.ID.eq(REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID))
                  .and(REPORTS.ID.eq(REPORT_SYSTEM_METRICS.REPORT_ID))
                  .leftJoin(REPORT_SYSTEM_METRIC_TARGETS)
                  .on(REPORT_SYSTEM_METRIC_TARGETS.SYSTEM_METRIC_ID.eq(SYSTEM_METRICS.ID))
                  .and(REPORT_SYSTEM_METRIC_TARGETS.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                  .and(REPORT_SYSTEM_METRIC_TARGETS.YEAR.eq(DSL.year(REPORTS.END_DATE)))
                  .orderBy(SYSTEM_METRICS.REFERENCE, SYSTEM_METRICS.ID)
          )
          .convertFrom { results ->
            results.map { ReportSystemMetricModel.of(it, systemValueField) }
          }
}
