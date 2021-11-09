package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.math.BigDecimal
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns with decimal values. */
class BigDecimalField(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, BigDecimal?>,
    override val table: SearchTable
) : SingleColumnSearchField<BigDecimal>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val bigDecimalValues = fieldNode.values.map { if (it != null) BigDecimal(it) else null }
    val nonNullValues = bigDecimalValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        DSL.or(
            listOfNotNull(
                if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      }
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for numeric fields")
      SearchFilterType.Range -> rangeCondition(bigDecimalValues)
    }
  }

  override fun computeValue(record: Record) = record[databaseField]?.toPlainString()
}
