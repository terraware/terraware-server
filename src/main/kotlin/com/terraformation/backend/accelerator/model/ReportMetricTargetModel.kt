package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.StandardIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.REPORT_AUTO_CALCULATED_INDICATOR_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATOR_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_INDICATOR_TARGETS
import org.jooq.Record

data class ReportProjectMetricTargetModel(
    val metricId: ProjectIndicatorId,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportProjectMetricTargetModel {
      return with(REPORT_PROJECT_INDICATOR_TARGETS) {
        ReportProjectMetricTargetModel(
            metricId = record[PROJECT_INDICATOR_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}

data class ReportStandardMetricTargetModel(
    val metricId: StandardIndicatorId,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportStandardMetricTargetModel {
      return with(REPORT_STANDARD_INDICATOR_TARGETS) {
        ReportStandardMetricTargetModel(
            metricId = record[STANDARD_INDICATOR_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}

data class ReportSystemMetricTargetModel(
    val metric: AutoCalculatedIndicator,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportSystemMetricTargetModel {
      return with(REPORT_AUTO_CALCULATED_INDICATOR_TARGETS) {
        ReportSystemMetricTargetModel(
            metric = record[AUTO_CALCULATED_INDICATOR_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}
