package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import org.jooq.Record

data class CommonIndicatorModel<ID : CommonIndicatorId?>(
    val id: ID,
    val category: IndicatorCategory,
    val description: String?,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val refId: String,
    val unit: String? = null,
) {
  companion object {
    fun of(record: Record): ExistingCommonIndicatorModel {
      return with(COMMON_INDICATORS) {
        ExistingCommonIndicatorModel(
            id = record[ID]!!,
            category = record[CATEGORY_ID]!!,
            description = record[DESCRIPTION],
            isPublishable = record[IS_PUBLISHABLE]!!,
            level = record[LEVEL_ID]!!,
            name = record[NAME]!!,
            refId = record[REF_ID]!!,
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingCommonIndicatorModel = CommonIndicatorModel<CommonIndicatorId>

typealias NewCommonIndicatorModel = CommonIndicatorModel<Nothing?>
