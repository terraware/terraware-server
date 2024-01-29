package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import org.jooq.Condition
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for ID columns that use wrapper types. */
class IdWrapperField<T : Any>(
    override val fieldName: String,
    override val databaseField: TableField<*, T?>,
    override val table: SearchTable,
    private val fromLong: (Long) -> T,
) : SingleColumnSearchField<T>() {
  override val nullable: Boolean
    get() = false

  override fun getCondition(fieldNode: FieldNode): Condition {
    val allValues = fieldNode.values.filterNotNull().map { fromLong(it.toLong()) }
    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (allValues.isNotEmpty()) databaseField.`in`(allValues) else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      SearchFilterType.ExactOrFuzzy,
      SearchFilterType.Fuzzy -> throw RuntimeException("Fuzzy search not supported for IDs")
      SearchFilterType.Range -> throw RuntimeException("Range search not supported for IDs")
    }
  }

  // IDs are already machine-readable.
  override fun raw(): SearchField? = null
}
