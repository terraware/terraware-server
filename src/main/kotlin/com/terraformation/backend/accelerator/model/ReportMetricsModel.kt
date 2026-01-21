package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ReportMetricStatus
import com.terraformation.backend.db.accelerator.SystemMetric
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
      return with(REPORT_STANDARD_METRICS) {
        ReportMetricEntryModel(
            target = record[TARGET],
            value = record[VALUE],
            modifiedBy = record[MODIFIED_BY],
            modifiedTime = record[MODIFIED_TIME],
            progressNotes =
                if (currentUser().canReadReportInternalComments()) {
                  record[PROGRESS_NOTES]
                } else {
                  null
                },
            projectsComments = record[PROJECTS_COMMENTS],
            status = record[STATUS_ID],
        )
      }
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
      return with(REPORT_PROJECT_METRICS) {
        ReportMetricEntryModel(
            target = record[TARGET],
            value = record[VALUE],
            modifiedBy = record[MODIFIED_BY],
            modifiedTime = record[MODIFIED_TIME],
            progressNotes =
                if (currentUser().canReadReportInternalComments()) {
                  record[PROGRESS_NOTES]
                } else {
                  null
                },
            projectsComments = record[PROJECTS_COMMENTS],
            status = record[STATUS_ID],
        )
      }
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
      return with(REPORT_SYSTEM_METRICS) {
        ReportSystemMetricEntryModel(
            target = record[TARGET],
            systemValue = record[systemValueField],
            systemTime = record[SYSTEM_TIME],
            overrideValue = record[OVERRIDE_VALUE],
            modifiedBy = record[MODIFIED_BY],
            modifiedTime = record[MODIFIED_TIME],
            progressNotes =
                if (currentUser().canReadReportInternalComments()) {
                  record[PROGRESS_NOTES]
                } else {
                  null
                },
            projectsComments = record[PROJECTS_COMMENTS],
            status = record[STATUS_ID],
        )
      }
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
