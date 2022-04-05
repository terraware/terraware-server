package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Search field superclass for columns with numeric values. Numeric values can always be searched
 * for exact values or ranges, but never with fuzzy matching.
 */
abstract class NumericSearchField<T : Number>(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, T?>,
    override val table: SearchTable,
    override val nullable: Boolean = true,
) : SingleColumnSearchField<T>() {
  /** Parses a string value into whatever numeric type this column uses. */
  abstract fun fromString(value: String): T

  override val supportedFilterTypes: Set<SearchFilterType> =
      EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition? {
    val numericValues = fieldNode.values.map { if (it != null) fromString(it) else null }
    val nonNullValues = numericValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        DSL.or(
            listOfNotNull(
                if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      }
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for numeric fields")
      SearchFilterType.Range -> rangeCondition(numericValues)
    }
  }
}
