package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import org.jooq.Record

data class CommonIndicatorModel<ID : CommonIndicatorId?>(
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
    fun of(record: Record): ExistingCommonIndicatorModel {
      return with(COMMON_INDICATORS) {
        ExistingCommonIndicatorModel(
            id = record[ID]!!,
            name = record[NAME]!!,
            description = record[DESCRIPTION],
            component = record[CATEGORY_ID]!!,
            type = record[LEVEL_ID]!!,
            reference = record[REF_ID]!!,
            isPublishable = record[IS_PUBLISHABLE]!!,
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingCommonIndicatorModel = CommonIndicatorModel<CommonIndicatorId>

typealias NewCommonIndicatorModel = CommonIndicatorModel<Nothing?>
