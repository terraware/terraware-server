package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat

/** Search field for numeric columns that don't allow fractional values. */
class LongField(
    fieldName: String,
    getDatabaseField: DatabaseFieldSupplier<Long>,
    table: SearchTable,
    localize: Boolean = true,
    exportable: Boolean = true,
) : NumericSearchField<Long>(fieldName, getDatabaseField, table, localize, exportable) {
  override fun fromString(value: String) =
      if (localize) numberFormat.parse(value).toLong() else value.toLong()

  override fun makeNumberFormat(): NumberFormat = NumberFormat.getIntegerInstance(currentLocale())

  override fun raw(): SearchField? {
    return if (localize) {
      LongField(rawFieldName(), getDatabaseField, table, false, false)
    } else {
      null
    }
  }

  override fun withTable(newTable: SearchTable): SearchField {
    return LongField(fieldName, getDatabaseField, newTable, localize, exportable)
  }
}
