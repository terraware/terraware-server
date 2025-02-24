package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRICS
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import org.jooq.Record

data class ReportMetricEntryModel(
    val target: Int? = null,
    val value: Int? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val notes: String? = null,
    val internalComment: String? = null,
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
            notes = record[NOTES],
            internalComment =
                if (currentUser().canReadReportInternalComments()) {
                  record[INTERNAL_COMMENT]
                } else {
                  null
                },
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
            notes = record[NOTES],
            internalComment =
                if (currentUser().canReadReportInternalComments()) {
                  record[INTERNAL_COMMENT]
                } else {
                  null
                },
        )
      }
    }
  }
}
