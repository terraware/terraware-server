package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import org.jooq.TableField

/** Search field for columns with floating-point values. */
class DoubleField(
    fieldName: String,
    databaseField: TableField<*, Double?>,
    table: SearchTable,
    nullable: Boolean = true,
) : NumericSearchField<Double>(fieldName, databaseField, table, nullable) {
  override fun fromString(value: String) = numberFormat.parse(value).toDouble()

  override fun makeNumberFormat(): NumberFormat {
    return NumberFormat.getNumberInstance(currentLocale()).apply {
      maximumFractionDigits = MAXIMUM_FRACTION_DIGITS
    }
  }
}
