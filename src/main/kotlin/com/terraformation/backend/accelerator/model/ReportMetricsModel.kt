package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ReportMetricStatus
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_PROJECT_METRIC_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_STANDARD_METRIC_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_SYSTEM_METRIC_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_SYSTEM_METRICS
import com.terraformation.backend.db.accelerator.tables.references.SYSTEM_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class ReportMetricEntryModel(
    val target: Int? = null,
    val value: Int? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val projectsComments: String? = null,
    val progressNotes: String? = null,
    val status: ReportMetricStatus? = null,
)

data class ReportStandardMetricModel(
    val metric: ExistingStandardMetricModel,
    val entry: ReportMetricEntryModel,
) {
  companion object {
    fun of(record: Record): ReportStandardMetricModel {
      return ReportStandardMetricModel(
          metric = ExistingStandardMetricModel.of(record),
          entry = entry(record),
      )
    }

    private fun entry(record: Record): ReportMetricEntryModel {
      return ReportMetricEntryModel(
          target = record[PROJECT_STANDARD_METRIC_TARGETS.TARGET],
          value = record[REPORT_STANDARD_METRICS.VALUE],
          modifiedBy = record[REPORT_STANDARD_METRICS.MODIFIED_BY],
          modifiedTime = record[REPORT_STANDARD_METRICS.MODIFIED_TIME],
          progressNotes =
              if (currentUser().canReadReportInternalComments()) {
                record[REPORT_STANDARD_METRICS.PROGRESS_NOTES]
              } else {
                null
              },
          projectsComments = record[REPORT_STANDARD_METRICS.PROJECTS_COMMENTS],
          status = record[REPORT_STANDARD_METRICS.STATUS_ID],
      )
    }
  }
}

data class ReportProjectMetricModel(
    val metric: ExistingProjectMetricModel,
    val entry: ReportMetricEntryModel,
) {
  companion object {
    fun of(record: Record): ReportProjectMetricModel {
      return ReportProjectMetricModel(
          metric = ExistingProjectMetricModel.of(record),
          entry = entry(record),
      )
    }

    private fun entry(record: Record): ReportMetricEntryModel {
      return ReportMetricEntryModel(
          target = record[PROJECT_PROJECT_METRIC_TARGETS.TARGET],
          value = record[REPORT_PROJECT_METRICS.VALUE],
          modifiedBy = record[REPORT_PROJECT_METRICS.MODIFIED_BY],
          modifiedTime = record[REPORT_PROJECT_METRICS.MODIFIED_TIME],
          progressNotes =
              if (currentUser().canReadReportInternalComments()) {
                record[REPORT_PROJECT_METRICS.PROGRESS_NOTES]
              } else {
                null
              },
          projectsComments = record[REPORT_PROJECT_METRICS.PROJECTS_COMMENTS],
          status = record[REPORT_PROJECT_METRICS.STATUS_ID],
      )
    }
  }
}

data class ReportSystemMetricEntryModel(
    val target: Int? = null,
    val systemValue: Int?,
    /** Time when system value is recorded. If null, the system value is current. */
    val systemTime: Instant? = null,
    val overrideValue: Int? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val progressNotes: String? = null,
    val projectsComments: String? = null,
    val status: ReportMetricStatus? = null,
) {
  companion object {
    fun of(record: Record, systemValueField: Field<Int?>): ReportSystemMetricEntryModel {
      return ReportSystemMetricEntryModel(
          target = record[PROJECT_SYSTEM_METRIC_TARGETS.TARGET],
          systemValue = record[systemValueField],
          systemTime = record[REPORT_SYSTEM_METRICS.SYSTEM_TIME],
          overrideValue = record[REPORT_SYSTEM_METRICS.OVERRIDE_VALUE],
          modifiedBy = record[REPORT_SYSTEM_METRICS.MODIFIED_BY],
          modifiedTime = record[REPORT_SYSTEM_METRICS.MODIFIED_TIME],
          progressNotes =
              if (currentUser().canReadReportInternalComments()) {
                record[REPORT_SYSTEM_METRICS.PROGRESS_NOTES]
              } else {
                null
              },
          projectsComments = record[REPORT_SYSTEM_METRICS.PROJECTS_COMMENTS],
          status = record[REPORT_SYSTEM_METRICS.STATUS_ID],
      )
    }
  }
}

data class ReportSystemMetricModel(
    val metric: SystemMetric,
    val entry: ReportSystemMetricEntryModel,
) {
  companion object {
    fun of(record: Record, systemValueField: Field<Int?>): ReportSystemMetricModel {
      return ReportSystemMetricModel(
          metric = record[SYSTEM_METRICS.ID.asNonNullable()],
          entry = ReportSystemMetricEntryModel.of(record, systemValueField),
      )
    }
  }
}
