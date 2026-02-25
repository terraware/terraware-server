package com.terraformation.backend.accelerator.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.tables.references.AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_AUTO_CALCULATED_INDICATOR_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_COMMON_INDICATOR_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATOR_TARGETS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class ReportIndicatorEntryModel(
    val target: Int? = null,
    val value: Int? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val projectsComments: String? = null,
    val progressNotes: String? = null,
    val status: ReportIndicatorStatus? = null,
)

data class ReportCommonIndicatorModel(
    val indicator: ExistingCommonIndicatorModel,
    val entry: ReportIndicatorEntryModel,
) {
  companion object {
    fun of(record: Record): ReportCommonIndicatorModel {
      return ReportCommonIndicatorModel(
          indicator = ExistingCommonIndicatorModel.of(record),
          entry = entry(record),
      )
    }

    private fun entry(record: Record): ReportIndicatorEntryModel {
      return with(REPORT_COMMON_INDICATORS) {
        ReportIndicatorEntryModel(
            target = record[REPORT_COMMON_INDICATOR_TARGETS.TARGET],
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

data class ReportProjectIndicatorModel(
    val indicator: ExistingProjectIndicatorModel,
    val entry: ReportIndicatorEntryModel,
) {
  companion object {
    fun of(record: Record): ReportProjectIndicatorModel {
      return ReportProjectIndicatorModel(
          indicator = ExistingProjectIndicatorModel.of(record),
          entry = entry(record),
      )
    }

    private fun entry(record: Record): ReportIndicatorEntryModel {
      return with(REPORT_PROJECT_INDICATORS) {
        ReportIndicatorEntryModel(
            target = record[REPORT_PROJECT_INDICATOR_TARGETS.TARGET],
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

data class ReportAutoCalculatedIndicatorEntryModel(
    val target: Int? = null,
    val systemValue: Int?,
    /** Time when system value is recorded. If null, the system value is current. */
    val systemTime: Instant? = null,
    val overrideValue: Int? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val progressNotes: String? = null,
    val projectsComments: String? = null,
    val status: ReportIndicatorStatus? = null,
) {
  companion object {
    fun of(record: Record, systemValueField: Field<Int?>): ReportAutoCalculatedIndicatorEntryModel {
      return with(REPORT_AUTO_CALCULATED_INDICATORS) {
        ReportAutoCalculatedIndicatorEntryModel(
            target = record[REPORT_AUTO_CALCULATED_INDICATOR_TARGETS.TARGET],
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

data class ReportAutoCalculatedIndicatorModel(
    val indicator: AutoCalculatedIndicator,
    val entry: ReportAutoCalculatedIndicatorEntryModel,
) {
  companion object {
    fun of(record: Record, systemValueField: Field<Int?>): ReportAutoCalculatedIndicatorModel {
      return ReportAutoCalculatedIndicatorModel(
          indicator = record[AUTO_CALCULATED_INDICATORS.ID.asNonNullable()],
          entry = ReportAutoCalculatedIndicatorEntryModel.of(record, systemValueField),
      )
    }
  }
}
