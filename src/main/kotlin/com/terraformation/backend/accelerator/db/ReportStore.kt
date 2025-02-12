package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.tables.daos.ReportsDao
import com.terraformation.backend.db.accelerator.tables.pojos.ReportsRow
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_REPORT_CONFIGS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.time.InstantSource
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

/** Store class intended for accelerator-admin to configure reports and metrics. */
@Named
class ReportStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
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
                } ?: throw IllegalStateException("Failed to insert project report config. ")
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

  fun reviewReportStandardMetrics(
      reportId: ReportId,
      entries: Map<StandardMetricId, ReportStandardMetricEntryModel>
  ) {
    requirePermissions { reviewReports() }

    reportsDao.fetchOneById(reportId) ?: throw ReportNotFoundException(reportId)

    upsertReportStandardMetrics(reportId, entries)
  }

  fun updateReportStandardMetrics(
      reportId: ReportId,
      entries: Map<StandardMetricId, ReportStandardMetricEntryModel>
  ) {
    requirePermissions { updateReport(reportId) }

    val report =
        fetchByCondition(REPORTS.ID.eq(reportId)).firstOrNull()
            ?: throw ReportNotFoundException(reportId)

    if (report.status != ReportStatus.NotSubmitted) {
      throw IllegalStateException(
          "Cannot update metrics for report $reportId of status ${report.status.name}")
    }

    upsertReportStandardMetrics(reportId, entries)
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

    return rows
  }

  private fun fetchByCondition(
      condition: Condition,
      includeMetrics: Boolean = false
  ): List<ReportModel> {
    val standardMetricsField =
        if (includeMetrics) {
          standardMetricsMultiset
        } else {
          null
        }

    return dslContext
        .select(REPORTS.asterisk(), standardMetricsField)
        .from(REPORTS)
        .where(condition)
        .fetch { ReportModel.of(it, standardMetricsField) }
        .filter { currentUser().canReadReport(it.id) }
  }

  private fun fetchConfigsByCondition(
      condition: Condition
  ): List<ExistingProjectReportConfigModel> {
    return with(PROJECT_REPORT_CONFIGS) {
      dslContext.selectFrom(this).where(condition).fetch { ProjectReportConfigModel.of(it) }
    }
  }

  private fun upsertReportStandardMetrics(
      reportId: ReportId,
      entries: Map<StandardMetricId, ReportStandardMetricEntryModel>
  ) {
    val columns =
        if (currentUser().canReviewReports()) {
          listOf(
              REPORT_STANDARD_METRICS.REPORT_ID,
              REPORT_STANDARD_METRICS.STANDARD_METRIC_ID,
              REPORT_STANDARD_METRICS.TARGET,
              REPORT_STANDARD_METRICS.VALUE,
              REPORT_STANDARD_METRICS.NOTES,
              REPORT_STANDARD_METRICS.INTERNAL_COMMENT,
              REPORT_STANDARD_METRICS.MODIFIED_BY,
              REPORT_STANDARD_METRICS.MODIFIED_TIME,
          )
        } else {
          listOf(
              REPORT_STANDARD_METRICS.REPORT_ID,
              REPORT_STANDARD_METRICS.STANDARD_METRIC_ID,
              REPORT_STANDARD_METRICS.TARGET,
              REPORT_STANDARD_METRICS.VALUE,
              REPORT_STANDARD_METRICS.NOTES,
              REPORT_STANDARD_METRICS.MODIFIED_BY,
              REPORT_STANDARD_METRICS.MODIFIED_TIME,
          )
        }

    dslContext.transaction { _ ->
      val rowsUpdated =
          dslContext
              .insertInto(
                  REPORT_STANDARD_METRICS,
                  columns,
              )
              .apply {
                entries.forEach { (metricId, entry) ->
                  if (currentUser().canReviewReports()) {
                    this.values(
                        reportId,
                        metricId,
                        entry.target,
                        entry.value,
                        entry.notes,
                        currentUser().userId,
                        clock.instant(),
                    )
                  } else {
                    this.values(
                        reportId,
                        metricId,
                        entry.target,
                        entry.value,
                        entry.notes,
                        entry.internalComment,
                        currentUser().userId,
                        clock.instant(),
                    )
                  }
                }
              }
              .onConflict(
                  REPORT_STANDARD_METRICS.REPORT_ID, REPORT_STANDARD_METRICS.STANDARD_METRIC_ID)
              .doUpdate()
              .setAllToExcluded()
              .execute()

      if (rowsUpdated > 0) {
        dslContext
            .update(REPORTS)
            .set(REPORTS.MODIFIED_BY, currentUser().userId)
            .set(REPORTS.MODIFIED_TIME, clock.instant())
            .where(REPORTS.ID.eq(reportId))
            .execute()
      }
    }
  }

  private val standardMetricsMultiset: Field<List<ReportStandardMetricModel>>
    get() {
      return DSL.multiset(
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
    }
}
