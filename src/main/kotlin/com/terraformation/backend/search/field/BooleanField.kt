package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns with boolean values. */
class BooleanField(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, Boolean?>,
    override val table: SearchTable,
    override val nullable: Boolean = true,
) : SingleColumnSearchField<Boolean>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val booleanValues =
        fieldNode.values.map { stringValue ->
          when (stringValue?.lowercase()) {
            "true" -> true
            "false" -> false
            null -> null
            else -> throw IllegalArgumentException("Unrecognized value $stringValue")
          }
        }
    val nonNullValues = booleanValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        DSL.or(
            listOfNotNull(
                if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      }
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for boolean fields")
      SearchFilterType.Range ->
          throw RuntimeException("Range search not supported for boolean fields")
    }
  }

  override fun computeValue(record: Record) =
      when (record[databaseField]) {
        true -> "true"
        false -> "false"
        null -> null
      }
}
