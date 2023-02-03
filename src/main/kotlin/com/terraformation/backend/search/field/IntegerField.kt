package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import org.jooq.TableField

/** Search field for numeric columns that don't allow fractional values. */
class IntegerField(
    fieldName: String,
    databaseField: TableField<*, Int?>,
    table: SearchTable,
    nullable: Boolean = true,
) : NumericSearchField<Int>(fieldName, databaseField, table, nullable) {
  override fun fromString(value: String) = numberFormat.parse(value).toInt()
  override fun makeNumberFormat(): NumberFormat = NumberFormat.getIntegerInstance(currentLocale())
}
