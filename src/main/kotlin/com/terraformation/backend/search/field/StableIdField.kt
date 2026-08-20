package com.terraformation.backend.search.field

import com.terraformation.backend.db.StableId
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchTable
import kotlin.collections.filterNotNull
import kotlin.collections.map

/** Search field for Stable ID because it uses a wrapper type. */
class StableIdField(
    fieldName: String,
    getDatabaseField: DatabaseFieldSupplier<StableId>,
    table: SearchTable,
) : IdField<StableId>(fieldName, getDatabaseField, table) {

  override fun getAllFieldNodeValues(fieldNode: FieldNode): List<StableId?> =
      fieldNode.values.filterNotNull().map { StableId(it) }

  override fun withTable(newTable: SearchTable): SearchField {
    return StableIdField(fieldName, getDatabaseField, newTable)
  }
}
