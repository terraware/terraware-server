package com.terraformation.backend.search.field

import com.terraformation.backend.db.collation
import com.terraformation.backend.db.likeFuzzy
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/** Case-insensitive search for fields whose values are always upper case. */
class UpperCaseTextField(
    override val fieldName: String,
    override val databaseField: Field<String?>,
    override val table: SearchTable,
) : SingleColumnSearchField<String>() {
  override val localize: Boolean
    get() = false

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Fuzzy, SearchFilterType.PhraseMatch)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val nonNullValues = fieldNode.values.mapNotNull { it?.uppercase() }
    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        DSL.or(
            listOfNotNull(if (fieldNode.values.any { it == null }) databaseField.isNull else null)
                .plus(nonNullValues.map { databaseField.contains(it) })
        )
      }
      SearchFilterType.ExactOrFuzzy,
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
                  }
          )
      SearchFilterType.PhraseMatch -> {
        DSL.or(
            listOfNotNull(if (fieldNode.values.any { it == null }) databaseField.isNull else null)
                .plus(phaseMatchCondition(nonNullValues))
        )
      }
      SearchFilterType.Range ->
          throw IllegalArgumentException("Range search not supported for text fields")
    }
  }

  override val orderByField: Field<*>
    get() = databaseField.collate(currentLocale().collation)

  // Text fields aren't localized.
  override fun raw(): SearchField? = null
}
