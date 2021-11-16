package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.*
import org.jooq.Condition
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for numeric columns that don't allow fractional values. */
class IntegerField(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, Int?>,
    override val table: SearchTable,
    override val nullable: Boolean = true
) : SingleColumnSearchField<Int>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val intValues = fieldNode.values.map { it?.toInt() }
    val nonNullValues = intValues.filterNotNull()
    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null,
              ))
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for numeric fields")
      SearchFilterType.Range -> rangeCondition(intValues)
    }
  }
}
