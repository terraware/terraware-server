package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ReportSubmittedEvent
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIdConverter
import com.terraformation.backend.db.accelerator.ReportMetricStatusConverter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.daos.ReportsDao
import com.terraformation.backend.db.accelerator.tables.pojos.ReportsRow
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_REPORT_CONFIGS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_CHALLENGES
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_SYSTEM_METRICS
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.SYSTEM_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserIdConverter
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.util.toInstant
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
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
                )),
        includeMetrics = includeMetrics)
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
    }
  }

  fun updateProjectReportConfig(
      projectId: ProjectId,
      reportingStartDate: LocalDate,
      reportingEndDate: LocalDate,
  ) {
    requirePermissions { manageProjectReportConfigs() }
    updateConfigDatesByCondition(
        PROJECT_REPORT_CONFIGS.PROJECT_ID.eq(projectId), reportingStartDate, reportingEndDate)
  }

  fun updateProjectReportConfig(
      configId: ProjectReportConfigId,
      reportingStartDate: LocalDate,
      reportingEndDate: LocalDate,
  ) {
    requirePermissions { manageProjectReportConfigs() }
    updateConfigDatesByCondition(
        PROJECT_REPORT_CONFIGS.ID.eq(configId), reportingStartDate, reportingEndDate)
  }

  fun fetchProjectReportConfigs(
      projectId: ProjectId? = null
  ): List<ExistingProjectReportConfigModel> {
    requirePermissions { manageProjectReportConfigs() }

    return fetchConfigsByCondition(
        projectId?.let { PROJECT_REPORT_CONFIGS.PROJECT_ID.eq(it) } ?: DSL.trueCondition())
  }

  fun refreshSystemMetricValues(reportId: ReportId, metrics: Collection<SystemMetric>) {
    requirePermissions { reviewReports() }

    val report =
        fetchByCondition(REPORTS.ID.eq(reportId)).firstOrNull()
            ?: throw ReportNotFoundException(reportId)

    if (report.status !in ReportModel.submittedStatuses) {
      throw IllegalStateException(
          "Cannot refresh the status of report $reportId with status ${report.status.name}")
    }

    dslContext.transaction { _ ->
      val rowsUpdated = updateReportSystemMetricWithTerrawareData(reportId, metrics)

      if (rowsUpdated > 0) {
        updateReportModifiedTime(reportId)
      }
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
  ) {
    requirePermissions { reviewReports() }

    val report =
        fetchByCondition(REPORTS.ID.eq(reportId)).firstOrNull()
            ?: throw ReportNotFoundException(reportId)

    if (report.status != status) {
      if (report.status !in ReportModel.submittedStatuses) {
        throw IllegalStateException(
            "Cannot change the status of report $reportId because it has " +
                "not been submitted. Current status: ${report.status.name}.")
      }
      if (status !in ReportModel.submittedStatuses) {
        throw IllegalStateException(
            "Cannot update the status of a report to ${status.name} " +
                "because it is not a submitted status.")
      }
    }

    dslContext.transaction { _ ->
      achievements?.let { mergeReportAchievements(reportId, it) }
      challenges?.let { mergeReportChallenges(reportId, it) }

      val rowsUpdated =
          with(REPORTS) {
            dslContext
                .update(this)
                .set(STATUS_ID, status)
                .set(HIGHLIGHTS, highlights)
                .set(FEEDBACK, feedback)
                .set(INTERNAL_COMMENT, internalComment)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
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

    val report =
        fetchByCondition(REPORTS.ID.eq(reportId), true).firstOrNull()
            ?: throw ReportNotFoundException(reportId)

    report.validateMetricEntries(
        standardMetricEntries = standardMetricEntries, projectMetricEntries = projectMetricEntries)

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

    eventPublisher.publishEvent(ReportSubmittedEvent(reportId))
  }

  fun updateReportQualitatives(
      reportId: ReportId,
      highlights: String?,
      achievements: List<String>,
      challenges: List<ReportChallengeModel>,
  ) {
    requirePermissions { updateReport(reportId) }

    val report =
        fetchByCondition(REPORTS.ID.eq(reportId), true).firstOrNull()
            ?: throw ReportNotFoundException(reportId)

    if (report.status != ReportStatus.NotSubmitted) {
      throw IllegalStateException(
          "Cannot update qualitatives data for report $reportId of status ${report.status.name}")
    }

    dslContext.transaction { _ ->
      mergeReportAchievements(reportId, achievements)
      mergeReportChallenges(reportId, challenges)

      dslContext
          .update(REPORTS)
          .set(REPORTS.HIGHLIGHTS, highlights)
          .set(REPORTS.MODIFIED_BY, currentUser().userId)
          .set(REPORTS.MODIFIED_TIME, clock.instant())
          .where(REPORTS.ID.eq(reportId))
          .execute()
    }
  }

  fun updateReportMetrics(
      reportId: ReportId,
      standardMetricEntries: Map<StandardMetricId, ReportMetricEntryModel> = emptyMap(),
      systemMetricEntries: Map<SystemMetric, ReportMetricEntryModel> = emptyMap(),
      projectMetricEntries: Map<ProjectMetricId, ReportMetricEntryModel> = emptyMap(),
  ) {
    requirePermissions { updateReport(reportId) }

    val report =
        fetchByCondition(REPORTS.ID.eq(reportId), true).firstOrNull()
            ?: throw ReportNotFoundException(reportId)

    if (report.status != ReportStatus.NotSubmitted) {
      throw IllegalStateException(
          "Cannot update metrics for report $reportId of status ${report.status.name}")
    }

    report.validateMetricEntries(
        standardMetricEntries = standardMetricEntries, projectMetricEntries = projectMetricEntries)

    dslContext.transaction { _ ->
      val rowsUpdated =
          upsertReportStandardMetrics(reportId, standardMetricEntries, false) +
              upsertReportSystemMetrics(reportId, systemMetricEntries, false) +
              upsertReportProjectMetrics(reportId, projectMetricEntries, false)
      if (rowsUpdated > 0) {
        updateReportModifiedTime(reportId)
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

      rows.add(
          ReportsRow(
              configId = config.id,
              projectId = config.projectId,
              statusId = ReportStatus.NotSubmitted,
              startDate = reportStartDate,
              endDate = reportEndDate,
              createdBy = systemUser.userId,
              createdTime = now,
              modifiedBy = systemUser.userId,
              modifiedTime = now,
          ))
    } while (!startDate.isAfter(config.reportingEndDate))

    // Set the first and last report to take account of config dates
    rows[0] = rows[0].copy(startDate = config.reportingStartDate)
    rows[rows.lastIndex] = rows[rows.lastIndex].copy(endDate = config.reportingEndDate)

    return rows
  }

  private fun updateReportRows(
      config: ExistingProjectReportConfigModel,
  ) {
    val existingReports = fetchByCondition(REPORTS.CONFIG_ID.eq(config.id))
    val newReportRows = createReportRows(config)

    if (existingReports.isEmpty()) {
      reportsDao.insert(newReportRows)
      return
    }

    if (newReportRows.isEmpty()) {
      reportsDao.update(
          existingReports.map {
            it.toRow()
                .copy(
                    statusId = ReportStatus.NotNeeded,
                    modifiedBy = systemUser.userId,
                    modifiedTime = clock.instant(),
                    submittedBy = null,
                    submittedTime = null,
                )
          })
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
      if (existingReport.endDate.isBefore(desiredReportRow.startDate!!)) {
        // If existing report date is before the new report date, archive it
        if (existingReport.status != ReportStatus.NotNeeded) {
          reportRowsToUpdate.add(
              existingReport
                  .toRow()
                  .copy(
                      statusId = ReportStatus.NotNeeded,
                      modifiedBy = systemUser.userId,
                      modifiedTime = clock.instant(),
                      submittedBy = null,
                      submittedTime = null,
                  ))
          if (existingReportIterator.hasNext()) {
            existingReport = existingReportIterator.next()
          } else {
            reportRowsToAdd.add(desiredReportRow)
            break
          }
        }
      } else if (desiredReportRow.endDate!!.isBefore(existingReport.startDate)) {
        // If the new report date is before the existing report date, this report needs to be added
        reportRowsToAdd.add(desiredReportRow)
        if (desiredReportIterator.hasNext()) {
          desiredReportRow = desiredReportIterator.next()
        } else {
          reportRowsToUpdate.add(
              existingReport
                  .toRow()
                  .copy(
                      statusId = ReportStatus.NotNeeded,
                      modifiedBy = systemUser.userId,
                      modifiedTime = clock.instant(),
                      submittedBy = null,
                      submittedTime = null,
                  ))
          break
        }
      } else {
        // If the report dates overlap, they point to the same reporting period. Update the
        // existing report dates to match the desired report dates, and un-archive
        if (existingReport.startDate != desiredReportRow.startDate!! ||
            existingReport.endDate != desiredReportRow.endDate!!) {
          reportRowsToUpdate.add(
              existingReport
                  .toRow()
                  .copy(
                      statusId = ReportStatus.NotSubmitted,
                      startDate = desiredReportRow.startDate!!,
                      endDate = desiredReportRow.endDate!!,
                      modifiedBy = systemUser.userId,
                      modifiedTime = clock.instant(),
                      submittedBy = null,
                      submittedTime = null,
                  ))
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
            .filter { it.status != ReportStatus.NotNeeded }
            .map { it.toRow().copy(statusId = ReportStatus.NotNeeded) })

    // Mark any unmatched new reports to be added
    reportRowsToAdd.addAll(desiredReportIterator.asSequence())

    dslContext.transaction { _ ->
      reportsDao.insert(reportRowsToAdd)
      reportsDao.update(reportRowsToUpdate)
    }
  }

  private fun fetchByCondition(
      condition: Condition,
      includeMetrics: Boolean = false
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

    return dslContext
        .select(
            REPORTS.asterisk(),
            PROJECT_REPORT_CONFIGS.REPORT_FREQUENCY_ID,
            projectMetricsField,
            standardMetricsField,
            systemMetricsField,
            achievementsMultiset,
            challengesMultiset,
        )
        .from(REPORTS)
        .join(PROJECT_REPORT_CONFIGS)
        .on(PROJECT_REPORT_CONFIGS.ID.eq(REPORTS.CONFIG_ID))
        .where(condition)
        .orderBy(REPORTS.START_DATE)
        .fetch {
          ReportModel.of(
              record = it,
              projectMetricsField = projectMetricsField,
              standardMetricsField = standardMetricsField,
              systemMetricsField = systemMetricsField,
              achievementsField = achievementsMultiset,
              challengesField = challengesMultiset,
          )
        }
        .filter { currentUser().canReadReport(it.id) }
  }

  private fun fetchConfigsByCondition(
      condition: Condition
  ): List<ExistingProjectReportConfigModel> {
    return with(PROJECT_REPORT_CONFIGS) {
      dslContext.selectFrom(this).where(condition).fetch { ProjectReportConfigModel.of(it) }
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

  private fun mergeReportAchievements(
      reportId: ReportId,
      achievements: List<String>,
  ) {
    dslContext.transaction { _ ->
      with(REPORT_ACHIEVEMENTS) {
        if (achievements.isNotEmpty()) {
          var insertQuery = dslContext.insertInto(this, REPORT_ID, POSITION, ACHIEVEMENT)

          achievements.forEachIndexed { index, achievement ->
            insertQuery = insertQuery.values(reportId, index, achievement)
          }

          insertQuery.onConflict(REPORT_ID, POSITION).doUpdate().setAllToExcluded().execute()
        }

        dslContext
            .deleteFrom(this)
            .where(REPORT_ID.eq(reportId))
            .and(POSITION.ge(achievements.size))
            .execute()
      }
    }
  }

  private fun mergeReportChallenges(
      reportId: ReportId,
      challenges: List<ReportChallengeModel>,
  ) {
    dslContext.transaction { _ ->
      with(REPORT_CHALLENGES) {
        if (challenges.isNotEmpty()) {
          var insertQuery =
              dslContext.insertInto(this, REPORT_ID, POSITION, CHALLENGE, MITIGATION_PLAN)

          challenges.forEachIndexed { index, model ->
            insertQuery = insertQuery.values(reportId, index, model.challenge, model.mitigationPlan)
          }

          insertQuery.onConflict(REPORT_ID, POSITION).doUpdate().setAllToExcluded().execute()
        }

        dslContext
            .deleteFrom(this)
            .where(REPORT_ID.eq(reportId))
            .and(POSITION.ge(challenges.size))
            .execute()
      }
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
    val targetField = table.field("target", Int::class.java)!!
    val valueField = table.field("value", Int::class.java)!!
    val underperformanceJustificationField =
        table.field("underperformance_justification", String::class.java)!!
    val progressNotesField = table.field("progress_notes", String::class.java)!!
    val statusField =
        table.field(
            "status_id", SQLDataType.INTEGER.asConvertedDataType(ReportMetricStatusConverter()))!!
    val modifiedByField =
        table.field("modified_by", SQLDataType.BIGINT.asConvertedDataType(UserIdConverter()))!!
    val modifiedTimeField = table.field("modified_time", Instant::class.java)!!

    var insertQuery = dslContext.insertInto(table).set()

    val iterator = entries.iterator()

    while (iterator.hasNext()) {
      val (metricId, entry) = iterator.next()
      insertQuery =
          insertQuery
              .set(reportIdField, reportId)
              .set(metricIdField, metricId)
              .set(targetField, entry.target)
              .set(valueField, entry.value)
              .set(underperformanceJustificationField, entry.underperformanceJustification)
              .set(statusField, entry.status)
              .set(modifiedByField, currentUser().userId)
              .set(modifiedTimeField, clock.instant())
              .apply {
                if (updateProgressNotes) {
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

  private fun updateReportSystemMetricWithTerrawareData(
      reportId: ReportId,
      metrics: Collection<SystemMetric>
  ): Int {
    if (metrics.isEmpty()) {
      return 0
    }

    return with(REPORT_SYSTEM_METRICS) {
      dslContext
          .insertInto(
              this,
              REPORT_ID,
              SYSTEM_METRIC_ID,
              SYSTEM_VALUE,
              SYSTEM_TIME,
              MODIFIED_BY,
              MODIFIED_TIME)
          .select(
              DSL.select(
                      REPORTS.ID,
                      SYSTEM_METRICS.ID,
                      systemTerrawareValueField,
                      DSL.value(clock.instant()),
                      DSL.value(currentUser().userId),
                      DSL.value(clock.instant()))
                  .from(SYSTEM_METRICS)
                  .join(REPORTS)
                  .on(REPORTS.ID.eq(reportId))
                  .where(SYSTEM_METRICS.ID.`in`(metrics)))
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
          updateProgressNotes = updateProgressNotes)

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
              .set(REPORT_SYSTEM_METRICS.TARGET, entry.target)
              .set(
                  REPORT_SYSTEM_METRICS.UNDERPERFORMANCE_JUSTIFICATION,
                  entry.underperformanceJustification)
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
          updateProgressNotes = updateProgressNotes)

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
                  .orderBy(REPORT_ACHIEVEMENTS.POSITION))
          .convertFrom { result ->
            result.map { it[REPORT_ACHIEVEMENTS.ACHIEVEMENT.asNonNullable()] }
          }

  private val challengesMultiset: Field<List<ReportChallengeModel>> =
      DSL.multiset(
              DSL.select(REPORT_CHALLENGES.CHALLENGE, REPORT_CHALLENGES.MITIGATION_PLAN)
                  .from(REPORT_CHALLENGES)
                  .where(REPORT_CHALLENGES.REPORT_ID.eq(REPORTS.ID))
                  .orderBy(REPORT_CHALLENGES.POSITION))
          .convertFrom { result -> result.map { ReportChallengeModel.of(it) } }

  private val standardMetricsMultiset: Field<List<ReportStandardMetricModel>> =
      DSL.multiset(
              DSL.select(
                      STANDARD_METRICS.asterisk(),
                      REPORT_STANDARD_METRICS.asterisk(),
                  )
                  .from(STANDARD_METRICS)
                  .leftJoin(REPORT_STANDARD_METRICS)
                  .on(STANDARD_METRICS.ID.eq(REPORT_STANDARD_METRICS.STANDARD_METRIC_ID))
                  .and(REPORTS.ID.eq(REPORT_STANDARD_METRICS.REPORT_ID))
                  .orderBy(STANDARD_METRICS.REFERENCE, STANDARD_METRICS.ID))
          .convertFrom { result -> result.map { ReportStandardMetricModel.of(it) } }

  private val projectMetricsMultiset: Field<List<ReportProjectMetricModel>> =
      DSL.multiset(
              DSL.select(
                      PROJECT_METRICS.asterisk(),
                      REPORT_PROJECT_METRICS.asterisk(),
                  )
                  .from(PROJECT_METRICS)
                  .leftJoin(REPORT_PROJECT_METRICS)
                  .on(PROJECT_METRICS.ID.eq(REPORT_PROJECT_METRICS.PROJECT_METRIC_ID))
                  .and(REPORTS.ID.eq(REPORT_PROJECT_METRICS.REPORT_ID))
                  .where(PROJECT_METRICS.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                  .orderBy(PROJECT_METRICS.REFERENCE, PROJECT_METRICS.ID))
          .convertFrom { result -> result.map { ReportProjectMetricModel.of(it) } }

  private val mortalityRateDenominatorField =
      with(OBSERVED_SITE_SPECIES_TOTALS) { DSL.sum(CUMULATIVE_DEAD) + DSL.sum(PERMANENT_LIVE) }

  private val mortalityRateNumeratorField =
      with(OBSERVED_SITE_SPECIES_TOTALS) { DSL.sum(CUMULATIVE_DEAD) }

  // Fetch the latest observations per planting site from the reporting period, and calculate the
  // mortality rate
  private val mortalityRateField =
      DSL.field(
              DSL.select(
                      DSL.if_(
                          mortalityRateDenominatorField.notEqual(BigDecimal.ZERO),
                          (mortalityRateNumeratorField * 100.0) / mortalityRateDenominatorField,
                          BigDecimal.ZERO))
                  .from(OBSERVED_SITE_SPECIES_TOTALS)
                  .where(
                      OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID.`in`(
                          DSL.select(OBSERVATIONS.ID)
                              .distinctOn(OBSERVATIONS.PLANTING_SITE_ID)
                              .from(OBSERVATIONS)
                              .join(PLANTING_SITES)
                              .on(PLANTING_SITES.ID.eq(OBSERVATIONS.PLANTING_SITE_ID))
                              .where(
                                  OBSERVATIONS.COMPLETED_TIME.lessThan(
                                      REPORTS.END_DATE.convertFrom {
                                        it!!.plusDays(1).toInstant(ZoneId.systemDefault())
                                      }))
                              .and(OBSERVATIONS.plantingSites.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                              .and(OBSERVATIONS.IS_AD_HOC.isFalse)
                              .orderBy(
                                  OBSERVATIONS.PLANTING_SITE_ID,
                                  OBSERVATIONS.COMPLETED_TIME.desc())))
                  .and(
                      OBSERVED_SITE_SPECIES_TOTALS.CERTAINTY_ID.notEqual(
                          RecordedSpeciesCertainty.Unknown)))
          .convertFrom { it.toInt() }

  private val seedsCollectedField =
      with(ACCESSIONS) {
        DSL.field(
                DSL.select(DSL.sum(EST_SEED_COUNT) + DSL.sum(TOTAL_WITHDRAWN_COUNT))
                    .from(this)
                    .where(PROJECT_ID.eq(REPORTS.PROJECT_ID))
                    .and(COLLECTED_DATE.between(REPORTS.START_DATE, REPORTS.END_DATE)))
            .convertFrom { it.toInt() }
      }

  private val withdrawnSeedlingsField =
      with(BATCH_WITHDRAWALS) {
        DSL.field(
            DSL.select(
                    DSL.sum(READY_QUANTITY_WITHDRAWN) +
                        DSL.sum(GERMINATING_QUANTITY_WITHDRAWN) +
                        DSL.sum(NOT_READY_QUANTITY_WITHDRAWN))
                .from(this)
                .where(BATCH_ID.eq(BATCHES.ID))
                .and(withdrawals.PURPOSE_ID.notEqual(WithdrawalPurpose.NurseryTransfer)))
      }

  private val seedlingsField =
      with(BATCHES) {
        DSL.field(
                DSL.select(
                        DSL.sum(READY_QUANTITY) +
                            DSL.sum(GERMINATING_QUANTITY) +
                            DSL.sum(NOT_READY_QUANTITY) +
                            DSL.coalesce(DSL.sum(withdrawnSeedlingsField), 0))
                    .from(this)
                    .where(PROJECT_ID.eq(REPORTS.PROJECT_ID))
                    .and(ADDED_DATE.between(REPORTS.START_DATE, REPORTS.END_DATE)))
            .convertFrom { it.toInt() }
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
                        .join(WITHDRAWALS)
                        .on(WITHDRAWALS.ID.eq(DELIVERIES.WITHDRAWAL_ID))
                        .join(PLANTING_SITES)
                        .on(PLANTING_SITES.ID.eq(PLANTING_SITE_ID))
                        .where(
                            WITHDRAWALS.WITHDRAWN_DATE.between(
                                REPORTS.START_DATE, REPORTS.END_DATE))
                        .and(PLANTING_SITES.PROJECT_ID.eq(REPORTS.PROJECT_ID))
                        .groupBy(SPECIES_ID)
                        .having(DSL.sum(NUM_PLANTS).ge(BigDecimal.ZERO))))
      }

  private val treesPlantedField =
      with(PLANTINGS) {
        DSL.field(
                DSL.select(DSL.sum(NUM_PLANTS))
                    .from(this)
                    .join(DELIVERIES)
                    .on(DELIVERIES.ID.eq(DELIVERY_ID))
                    .join(WITHDRAWALS)
                    .on(WITHDRAWALS.ID.eq(DELIVERIES.WITHDRAWAL_ID))
                    .join(PLANTING_SITES)
                    .on(PLANTING_SITES.ID.eq(PLANTING_SITE_ID))
                    .where(WITHDRAWALS.WITHDRAWN_DATE.between(REPORTS.START_DATE, REPORTS.END_DATE))
                    .and(PLANTING_SITES.PROJECT_ID.eq(REPORTS.PROJECT_ID)))
            .convertFrom { it.toInt() }
      }

  private val systemTerrawareValueField =
      DSL.coalesce(
          DSL.case_()
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.MortalityRate), mortalityRateField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.Seedlings), seedlingsField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.SeedsCollected), seedsCollectedField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.SpeciesPlanted), speciesPlantedField)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.TreesPlanted), treesPlantedField)
              .else_(0),
          DSL.value(0))

  private val systemValueField =
      DSL.coalesce(REPORT_SYSTEM_METRICS.SYSTEM_VALUE, systemTerrawareValueField)

  private val systemMetricsMultiset: Field<List<ReportSystemMetricModel>> =
      DSL.multiset(
              DSL.select(
                      SYSTEM_METRICS.ID,
                      REPORT_SYSTEM_METRICS.asterisk(),
                      systemValueField,
                  )
                  .from(SYSTEM_METRICS)
                  .leftJoin(REPORT_SYSTEM_METRICS)
                  .on(SYSTEM_METRICS.ID.eq(REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID))
                  .and(REPORTS.ID.eq(REPORT_SYSTEM_METRICS.REPORT_ID))
                  .orderBy(SYSTEM_METRICS.REFERENCE, SYSTEM_METRICS.ID))
          .convertFrom { result -> result.map { ReportSystemMetricModel.of(it, systemValueField) } }
}
