package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import org.jooq.Record

data class CommonIndicatorModel<ID : CommonIndicatorId?>(
    val id: ID,
    val active: Boolean = true,
    val category: IndicatorCategory,
    val classId: IndicatorClass? = null,
    val description: String?,
    val frequency: IndicatorFrequency? = null,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val notes: String? = null,
    val primaryDataSource: String? = null,
    val refId: String,
    val tfOwner: String? = null,
    val unit: String? = null,
) {
  companion object {
    fun of(record: Record): ExistingCommonIndicatorModel {
      return with(COMMON_INDICATORS) {
        ExistingCommonIndicatorModel(
            id = record[ID]!!,
            active = record[ACTIVE]!!,
            category = record[CATEGORY_ID]!!,
            classId = record[CLASS_ID],
            description = record[DESCRIPTION],
            frequency = record[FREQUENCY_ID],
            isPublishable = record[IS_PUBLISHABLE]!!,
            level = record[LEVEL_ID]!!,
            name = record[NAME]!!,
            notes = record[NOTES],
            primaryDataSource = record[PRIMARY_DATA_SOURCE],
            refId = record[REF_ID]!!,
            tfOwner = record[TF_OWNER],
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingCommonIndicatorModel = CommonIndicatorModel<CommonIndicatorId>

typealias NewCommonIndicatorModel = CommonIndicatorModel<Nothing?>
