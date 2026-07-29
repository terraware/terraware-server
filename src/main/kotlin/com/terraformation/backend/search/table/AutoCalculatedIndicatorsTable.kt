package com.terraformation.backend.search.table

import com.terraformation.backend.db.accelerator.tables.references.AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class AutoCalculatedIndicatorsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = AUTO_CALCULATED_INDICATORS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          acceleratorReportAutoCalculatedIndicators.asMultiValueSublist(
              "reportIndicators",
              AUTO_CALCULATED_INDICATORS.ID.eq(
                  REPORT_AUTO_CALCULATED_INDICATORS.AUTO_CALCULATED_INDICATOR_ID
              ),
          )
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          booleanField("active", AUTO_CALCULATED_INDICATORS.ACTIVE),
          enumField("category", AUTO_CALCULATED_INDICATORS.CATEGORY_ID),
          enumField("class", AUTO_CALCULATED_INDICATORS.CLASS_ID),
          textField("description", AUTO_CALCULATED_INDICATORS.DESCRIPTION),
          enumField("frequency", AUTO_CALCULATED_INDICATORS.FREQUENCY_ID),
          nonLocalizableEnumField("indicator", AUTO_CALCULATED_INDICATORS.ID),
          enumField("level", AUTO_CALCULATED_INDICATORS.LEVEL_ID),
          textField("name", AUTO_CALCULATED_INDICATORS.NAME),
          integerField("precision", AUTO_CALCULATED_INDICATORS.PRECISION),
          booleanField("publishable", AUTO_CALCULATED_INDICATORS.IS_PUBLISHABLE),
          textField("refId", AUTO_CALCULATED_INDICATORS.REF_ID),
          textField("unit", AUTO_CALCULATED_INDICATORS.UNIT),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(AUTO_CALCULATED_INDICATORS.REF_ID, AUTO_CALCULATED_INDICATORS.ID)
}
