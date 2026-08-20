package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat

/** Search field for columns with floating-point values. */
class DoubleField(
    fieldName: String,
    getDatabaseField: DatabaseFieldSupplier<Double>,
    table: SearchTable,
    localize: Boolean = true,
    exportable: Boolean = true,
) : NumericSearchField<Double>(fieldName, getDatabaseField, table, localize, exportable) {
  override fun fromString(value: String) =
      if (localize) numberFormat.parse(value).toDouble() else value.toDouble()

  override fun makeNumberFormat(): NumberFormat {
    return NumberFormat.getNumberInstance(currentLocale()).apply {
      maximumFractionDigits = MAXIMUM_FRACTION_DIGITS
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      DoubleField(rawFieldName(), getDatabaseField, table, false, false)
    } else {
      null
    }
  }

  override fun withTable(newTable: SearchTable): SearchField {
    return DoubleField(fieldName, getDatabaseField, newTable, localize, exportable)
  }
}
