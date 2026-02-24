package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.REPORT_AUTO_CALCULATED_INDICATOR_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_COMMON_INDICATOR_TARGETS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATOR_TARGETS
import org.jooq.Record

data class ReportProjectIndicatorTargetModel(
    val indicatorId: ProjectIndicatorId,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportProjectIndicatorTargetModel {
      return with(REPORT_PROJECT_INDICATOR_TARGETS) {
        ReportProjectIndicatorTargetModel(
            indicatorId = record[PROJECT_INDICATOR_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}

data class ReportCommonIndicatorTargetModel(
    val indicatorId: CommonIndicatorId,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportCommonIndicatorTargetModel {
      return with(REPORT_COMMON_INDICATOR_TARGETS) {
        ReportCommonIndicatorTargetModel(
            indicatorId = record[COMMON_INDICATOR_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}

data class ReportAutoCalculatedIndicatorTargetModel(
    val indicator: AutoCalculatedIndicator,
    val target: Number?,
    val year: Number,
) {
  companion object {
    fun of(record: Record): ReportAutoCalculatedIndicatorTargetModel {
      return with(REPORT_AUTO_CALCULATED_INDICATOR_TARGETS) {
        ReportAutoCalculatedIndicatorTargetModel(
            indicator = record[AUTO_CALCULATED_INDICATOR_ID]!!,
            target = record[TARGET],
            year = record[YEAR]!!,
        )
      }
    }
  }
}
