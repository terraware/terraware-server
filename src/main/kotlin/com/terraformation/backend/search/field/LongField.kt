package com.terraformation.backend.search.field

import com.terraformation.backend.search.SearchTable
import org.jooq.TableField

/** Search field for numeric columns that don't allow fractional values. */
class LongField(
    fieldName: String,
    displayName: String,
    databaseField: TableField<*, Long?>,
    table: SearchTable,
    nullable: Boolean = true,
) : NumericSearchField<Long>(fieldName, displayName, databaseField, table, nullable) {
  override fun fromString(value: String) = value.toLong()
}
