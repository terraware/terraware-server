package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_METRIC_TARGETS
import org.jooq.Record

data class ReportProjectMetricTargetModel(
    val metricId: ProjectMetricId,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportProjectMetricTargetModel {
      return with(REPORT_PROJECT_METRIC_TARGETS) {
        ReportProjectMetricTargetModel(
            metricId = record[PROJECT_METRIC_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}

data class ReportStandardMetricTargetModel(
    val metricId: StandardMetricId,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportStandardMetricTargetModel {
      return with(
          com.terraformation.backend.db.accelerator.tables.references.REPORT_STANDARD_METRIC_TARGETS
      ) {
        ReportStandardMetricTargetModel(
            metricId = record[STANDARD_METRIC_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}

data class ReportSystemMetricTargetModel(
    val metric: SystemMetric,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportSystemMetricTargetModel {
      return with(
          com.terraformation.backend.db.accelerator.tables.references.REPORT_SYSTEM_METRIC_TARGETS
      ) {
        ReportSystemMetricTargetModel(
            metric = record[SYSTEM_METRIC_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}
