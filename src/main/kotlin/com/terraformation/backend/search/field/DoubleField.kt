package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns with floating-point values. */
class DoubleField(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, Double?>,
    override val table: SearchTable,
    override val nullable: Boolean = true,
) : SingleColumnSearchField<Double>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val doubleValues = fieldNode.values.map { it?.toDouble() }
    val nonNullValues = doubleValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        DSL.or(
            listOfNotNull(
                if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      }
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for numeric fields")
      SearchFilterType.Range -> rangeCondition(doubleValues)
    }
  }

  override fun computeValue(record: Record) = record[databaseField]?.toString()
}
