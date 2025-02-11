package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRICS
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import org.jooq.Record

data class ReportStandardMetricEntryModel(
    val target: Int? = null,
    val value: Int? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val notes: String? = null,
    val internalComment: String? = null,
) {

  companion object {
    fun of(record: Record): ReportStandardMetricEntryModel {
      return with(REPORT_STANDARD_METRICS) {
        ReportStandardMetricEntryModel(
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

data class ReportStandardMetricModel(
    val metric: ExistingStandardMetricModel,
    val entry: ReportStandardMetricEntryModel,
) {
  companion object {
    fun of(record: Record): ReportStandardMetricModel {
      return ReportStandardMetricModel(
          metric = ExistingStandardMetricModel.of(record),
          entry = ReportStandardMetricEntryModel.of(record),
      )
    }
  }
}
