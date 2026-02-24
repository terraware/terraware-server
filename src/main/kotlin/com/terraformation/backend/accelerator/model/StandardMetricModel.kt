package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.StandardIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_INDICATORS
import org.jooq.Record

data class StandardMetricModel<ID : StandardIndicatorId?>(
    val id: ID,
    val name: String,
    val description: String?,
    val component: IndicatorCategory,
    val type: IndicatorLevel,
    val reference: String,
    val isPublishable: Boolean,
    val unit: String? = null,
) {
  companion object {
    fun of(record: Record): ExistingStandardMetricModel {
      return with(STANDARD_INDICATORS) {
        ExistingStandardMetricModel(
            id = record[ID]!!,
            name = record[NAME]!!,
            description = record[DESCRIPTION],
            component = record[CATEGORY_ID]!!,
            type = record[LEVEL_ID]!!,
            reference = record[REFERENCE]!!,
            isPublishable = record[IS_PUBLISHABLE]!!,
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingStandardMetricModel = StandardMetricModel<StandardIndicatorId>

typealias NewStandardMetricModel = StandardMetricModel<Nothing?>
