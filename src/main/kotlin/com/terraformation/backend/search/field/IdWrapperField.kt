package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchTable

/** Search field for ID columns that use wrapper types. */
class IdWrapperField<T : Any>(
    fieldName: String,
    getDatabaseField: DatabaseFieldSupplier<T>,
    table: SearchTable,
    private val fromLong: (Long) -> T,
) : IdField<T>(fieldName, getDatabaseField, table) {
  override fun getAllFieldNodeValues(fieldNode: FieldNode): List<T?> =
      fieldNode.values.filterNotNull().map { fromLong(it.toLong()) }

  override fun withTable(newTable: SearchTable): SearchField {
    return IdWrapperField(fieldName, getDatabaseField, newTable, fromLong)
  }
}
