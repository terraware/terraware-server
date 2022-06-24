package com.terraformation.backend.search.field

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.UsesFuzzySearchOperators
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.*
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/** Case-insensitive search for fields whose values are always upper case. */
class UpperCaseTextField(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: Field<String?>,
    override val table: SearchTable,
    override val nullable: Boolean = true,
    override val fuzzySearchOperators: FuzzySearchOperators
) : SingleColumnSearchField<String>(), UsesFuzzySearchOperators {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Fuzzy)

  override fun getCondition(fieldNode: FieldNode): Condition {
    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        val values = fieldNode.values.mapNotNull { it?.uppercase() }
        DSL.or(
            listOfNotNull(
                if (values.isNotEmpty()) databaseField.`in`(values) else null,
                if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      }
      SearchFilterType.Fuzzy ->
          DSL.or(
              fieldNode.values
                  .map { it?.uppercase() }
                  .flatMap { value ->
                    if (value != null) {
                      listOf(databaseField.likeFuzzy(value), databaseField.like("$value%"))
                    } else {
                      listOf(databaseField.isNull)
                    }
                  })
      SearchFilterType.Range ->
          throw IllegalArgumentException("Range search not supported for text fields")
    }
  }
}
