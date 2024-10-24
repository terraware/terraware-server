package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import org.jooq.Field

/** Search field for columns with floating-point values. */
class DoubleField(
    fieldName: String,
    databaseField: Field<Double?>,
    table: SearchTable,
    localize: Boolean = true,
    exportable: Boolean = true,
) : NumericSearchField<Double>(fieldName, databaseField, table, localize, exportable) {
  override fun fromString(value: String) = numberFormat.parse(value).toDouble()

  override fun makeNumberFormat(): NumberFormat {
    return NumberFormat.getNumberInstance(currentLocale()).apply {
      maximumFractionDigits = MAXIMUM_FRACTION_DIGITS
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      DoubleField(rawFieldName(), databaseField, table, false, false)
    } else {
      null
    }
  }
}
