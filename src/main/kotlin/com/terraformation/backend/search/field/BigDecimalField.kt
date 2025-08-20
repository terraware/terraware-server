package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import org.jooq.Field

/** Search field for columns with decimal values. */
class BigDecimalField(
    fieldName: String,
    databaseField: Field<BigDecimal?>,
    table: SearchTable,
    localize: Boolean = true,
    exportable: Boolean = true,
) :
    NumericSearchField<BigDecimal>(
        fieldName,
        databaseField,
        table,
        localize = localize,
        exportable = exportable,
    ) {
  override fun fromString(value: String) = numberFormat.parseObject(value) as BigDecimal

  override fun makeNumberFormat(): NumberFormat {
    return (NumberFormat.getNumberInstance(currentLocale()) as DecimalFormat).apply {
      isParseBigDecimal = true
      maximumFractionDigits = MAXIMUM_FRACTION_DIGITS
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      BigDecimalField(rawFieldName(), databaseField, table, false, false)
    } else {
      null
    }
  }
}
