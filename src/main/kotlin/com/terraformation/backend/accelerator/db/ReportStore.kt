package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ReportSubmittedEvent
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectReportConfigModel
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
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIdConverter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.daos.ReportsDao
import com.terraformation.backend.db.accelerator.tables.pojos.ReportsRow
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_REPORT_CONFIGS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_SYSTEM_METRICS
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.SYSTEM_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserIdConverter
import jakarta.inject.Named
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

  fun fetchProjectReportConfigs(
      projectId: ProjectId? = null
  ): List<ExistingProjectReportConfigModel> {
    requirePermissions { manageProjectReportConfigs() }

    return fetchConfigsByCondition(
        projectId?.let { PROJECT_REPORT_CONFIGS.PROJECT_ID.eq(it) } ?: DSL.trueCondition())
  }

  fun reviewReport(
      reportId: ReportId,
      status: ReportStatus,
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

    val rowsUpdated =
        with(REPORTS) {
          dslContext
              .update(this)
              .set(STATUS_ID, status)
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

  fun reviewReportMetrics(
      reportId: ReportId,
      standardMetricEntries: Map<StandardMetricId, ReportMetricEntryModel> = emptyMap(),
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

    eventPublisher.publishEvent(ReportSubmittedEvent(reportId))
  }

  fun updateReportMetrics(
      reportId: ReportId,
      standardMetricEntries: Map<StandardMetricId, ReportMetricEntryModel> = emptyMap(),
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
              upsertReportProjectMetrics(reportId, projectMetricEntries, false)
      if (rowsUpdated > 0) {
        updateReportModifiedTime(reportId)
      }
    }
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

    val startYear = config.reportingStartDate.year
    val startMonth =
        when (config.frequency) {
          ReportFrequency.Quarterly -> config.reportingStartDate.month.firstMonthOfQuarter()
          ReportFrequency.Annual -> Month.JANUARY
        }

    var startDate = LocalDate.of(startYear, startMonth, 1)

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
        .select(REPORTS.asterisk(), projectMetricsField, standardMetricsField, systemMetricsField)
        .from(REPORTS)
        .where(condition)
        .fetch {
          ReportModel.of(
              record = it,
              projectMetricsField = projectMetricsField,
              standardMetricsField = standardMetricsField,
              systemMetricsField = systemMetricsField,
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

  private fun <ID : Any> upsertReportMetrics(
      reportId: ReportId,
      metricIdField: TableField<*, ID?>,
      entries: Map<ID, ReportMetricEntryModel>,
      updateInternalComment: Boolean,
  ): Int {
    val table = metricIdField.table!!
    val reportIdField =
        table.field("report_id", SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()))!!
    val targetField = table.field("target", Int::class.java)!!
    val valueField = table.field("value", Int::class.java)!!
    val notesField = table.field("notes", String::class.java)!!
    val internalCommentField = table.field("internal_comment", String::class.java)!!
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
              .set(notesField, entry.notes)
              .set(modifiedByField, currentUser().userId)
              .set(modifiedTimeField, clock.instant())
              .apply {
                if (updateInternalComment) {
                  this.set(internalCommentField, entry.internalComment)
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

  private fun upsertReportStandardMetrics(
      reportId: ReportId,
      entries: Map<StandardMetricId, ReportMetricEntryModel>,
      updateInternalComment: Boolean,
  ) =
      upsertReportMetrics(
          reportId = reportId,
          metricIdField = REPORT_STANDARD_METRICS.STANDARD_METRIC_ID,
          entries = entries,
          updateInternalComment = updateInternalComment)

  private fun upsertReportProjectMetrics(
      reportId: ReportId,
      entries: Map<ProjectMetricId, ReportMetricEntryModel>,
      updateInternalComment: Boolean,
  ) =
      upsertReportMetrics(
          reportId = reportId,
          metricIdField = REPORT_PROJECT_METRICS.PROJECT_METRIC_ID,
          entries = entries,
          updateInternalComment = updateInternalComment)

  private fun updateReportModifiedTime(reportId: ReportId) {
    dslContext
        .update(REPORTS)
        .set(REPORTS.MODIFIED_BY, currentUser().userId)
        .set(REPORTS.MODIFIED_TIME, clock.instant())
        .where(REPORTS.ID.eq(reportId))
        .execute()
  }

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

  private val systemValueField =
      DSL.coalesce(
          REPORT_SYSTEM_METRICS.SYSTEM_VALUE,
          DSL.case_()
              // ToDo: Implement each system query
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.MortalityRate), 1)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.Seedlings), 2)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.SeedsCollected), 3)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.SpeciesPlanted), 4)
              .`when`(SYSTEM_METRICS.ID.eq(SystemMetric.TreesPlanted), 5)
              .else_(0))

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
