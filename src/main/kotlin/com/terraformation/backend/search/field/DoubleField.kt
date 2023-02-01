package com.terraformation.backend.search.field

import com.terraformation.backend.search.SearchTable
import org.jooq.TableField

/** Search field for columns with floating-point values. */
class DoubleField(
    fieldName: String,
    databaseField: TableField<*, Double?>,
    table: SearchTable,
    nullable: Boolean = true,
) : NumericSearchField<Double>(fieldName, databaseField, table, nullable) {
  override fun fromString(value: String) = value.toDouble()
}
