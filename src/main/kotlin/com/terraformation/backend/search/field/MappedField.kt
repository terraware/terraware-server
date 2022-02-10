package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns that need to be dynamically mapped to a different data type. */
class MappedField<T : Any>(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, T?>,
    override val table: SearchTable,
    override val nullable: Boolean = true,
    private val convertSearchFilter: (String) -> T?,
    private val convertDatabaseValue: (T) -> String?,
) : SingleColumnSearchField<T>() {
  override fun getCondition(fieldNode: FieldNode): Condition {
    val allValues =
        fieldNode.values.filterNotNull().map {
          convertSearchFilter(it)
              ?: throw IllegalArgumentException("Value $it not recognized for $fieldName")
        }
    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          if (allValues.isNotEmpty()) databaseField.`in`(allValues) else DSL.falseCondition()
      SearchFilterType.Fuzzy -> throw RuntimeException("Fuzzy search not supported for this field")
      SearchFilterType.Range -> throw RuntimeException("Range search not supported for this field")
    }
  }

  override fun computeValue(record: Record): String? {
    return record[databaseField]?.let { convertDatabaseValue(it) }
  }
}
