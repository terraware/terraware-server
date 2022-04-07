package com.terraformation.backend.search.field

import com.terraformation.backend.search.SearchTable
import java.math.BigDecimal
import org.jooq.Record
import org.jooq.TableField

/** Search field for columns with decimal values. */
class BigDecimalField(
    fieldName: String,
    displayName: String,
    databaseField: TableField<*, BigDecimal?>,
    table: SearchTable,
) : NumericSearchField<BigDecimal>(fieldName, displayName, databaseField, table) {
  override fun fromString(value: String) = BigDecimal(value)
  override fun computeValue(record: Record) = record[databaseField]?.toPlainString()
}
