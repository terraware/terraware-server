package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat

/** Search field for numeric columns that don't allow fractional values. */
class IntegerField(
    fieldName: String,
    getDatabaseField: DatabaseFieldSupplier<Int>,
    table: SearchTable,
    localize: Boolean = true,
    exportable: Boolean = true,
) : NumericSearchField<Int>(fieldName, getDatabaseField, table, localize, exportable) {
  override fun fromString(value: String) =
      if (localize) numberFormat.parse(value).toInt() else value.toInt()

  override fun makeNumberFormat(): NumberFormat = NumberFormat.getIntegerInstance(currentLocale())

  override fun raw(): SearchField? {
    return if (localize) {
      IntegerField(rawFieldName(), getDatabaseField, table, false, false)
    } else {
      null
    }
  }

  override fun withTable(newTable: SearchTable): SearchField {
    return IntegerField(fieldName, getDatabaseField, newTable, localize, exportable)
  }
}
