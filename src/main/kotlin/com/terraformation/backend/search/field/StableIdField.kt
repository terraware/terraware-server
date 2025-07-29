package com.terraformation.backend.search.field

import com.terraformation.backend.db.StableId
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchTable
import kotlin.collections.filterNotNull
import kotlin.collections.map
import org.jooq.Field

/** Search field for Stable ID because it uses a wrapper type. */
class StableIdField(
    override val fieldName: String,
    override val databaseField: Field<StableId?>,
    override val table: SearchTable,
) : IdField<StableId>(fieldName, databaseField, table) {

  override fun getAllFieldNodeValues(fieldNode: FieldNode): List<StableId?> =
      fieldNode.values.filterNotNull().map { StableId(it) }
}
