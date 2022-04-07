package com.terraformation.backend.search.field

import com.terraformation.backend.search.SearchTable
import org.jooq.TableField

/** Search field for numeric columns that don't allow fractional values. */
class IntegerField(
    fieldName: String,
    displayName: String,
    databaseField: TableField<*, Int?>,
    table: SearchTable,
    nullable: Boolean = true,
) : NumericSearchField<Int>(fieldName, displayName, databaseField, table, nullable) {
  override fun fromString(value: String) = value.toInt()
}
