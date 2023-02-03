package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import org.jooq.TableField

/** Search field for columns with decimal values. */
class BigDecimalField(
    fieldName: String,
    databaseField: TableField<*, BigDecimal?>,
    table: SearchTable,
) : NumericSearchField<BigDecimal>(fieldName, databaseField, table) {
  override fun fromString(value: String) = numberFormat.parseObject(value) as BigDecimal

  override fun makeNumberFormat(): NumberFormat {
    return (NumberFormat.getNumberInstance(currentLocale()) as DecimalFormat).apply {
      isParseBigDecimal = true
      maximumFractionDigits = MAXIMUM_FRACTION_DIGITS
    }
  }
}
