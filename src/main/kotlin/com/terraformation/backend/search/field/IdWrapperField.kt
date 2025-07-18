package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchTable
import org.jooq.Field

/** Search field for ID columns that use wrapper types. */
class IdWrapperField<T : Any>(
    override val fieldName: String,
    override val databaseField: Field<T?>,
    override val table: SearchTable,
    private val fromLong: (Long) -> T,
) : IdField<T>(fieldName, databaseField, table) {
  override fun getAllFieldNodeValues(fieldNode: FieldNode): List<T?> =
      fieldNode.values.filterNotNull().map { fromLong(it.toLong()) }
}
