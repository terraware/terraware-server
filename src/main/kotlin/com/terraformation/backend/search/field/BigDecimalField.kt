package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat

/** Search field for columns with decimal values. */
class BigDecimalField(
    fieldName: String,
    getDatabaseField: DatabaseFieldSupplier<BigDecimal>,
    table: SearchTable,
    localize: Boolean = true,
    exportable: Boolean = true,
) :
    NumericSearchField<BigDecimal>(
        fieldName,
        getDatabaseField,
        table,
        localize = localize,
        exportable = exportable,
    ) {
  override fun fromString(value: String) =
      if (localize) numberFormat.parseObject(value) as BigDecimal else BigDecimal(value)

  override fun makeNumberFormat(): NumberFormat {
    return (NumberFormat.getNumberInstance(currentLocale()) as DecimalFormat).apply {
      isParseBigDecimal = true
      maximumFractionDigits = MAXIMUM_FRACTION_DIGITS
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      BigDecimalField(rawFieldName(), getDatabaseField, table, false, false)
    } else {
      null
    }
  }

  override fun withTable(newTable: SearchTable): SearchField {
    return BigDecimalField(fieldName, getDatabaseField, newTable, localize, exportable)
  }
}
