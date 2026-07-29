package com.terraformation.backend.search.table

import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_COMMON_INDICATORS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class CommonIndicatorsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = COMMON_INDICATORS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          acceleratorReportCommonIndicators.asMultiValueSublist(
              "reportIndicators",
              COMMON_INDICATORS.ID.eq(REPORT_COMMON_INDICATORS.COMMON_INDICATOR_ID),
          )
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          booleanField("active", COMMON_INDICATORS.ACTIVE),
          enumField("category", COMMON_INDICATORS.CATEGORY_ID),
          enumField("class", COMMON_INDICATORS.CLASS_ID),
          textField("description", COMMON_INDICATORS.DESCRIPTION),
          enumField("frequency", COMMON_INDICATORS.FREQUENCY_ID),
          idWrapperField("id", COMMON_INDICATORS.ID) { CommonIndicatorId(it) },
          enumField("level", COMMON_INDICATORS.LEVEL_ID),
          textField("name", COMMON_INDICATORS.NAME),
          integerField("precision", COMMON_INDICATORS.PRECISION),
          booleanField("publishable", COMMON_INDICATORS.IS_PUBLISHABLE),
          textField("refId", COMMON_INDICATORS.REF_ID),
          textField("unit", COMMON_INDICATORS.UNIT),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(COMMON_INDICATORS.REF_ID, COMMON_INDICATORS.ID)
}
