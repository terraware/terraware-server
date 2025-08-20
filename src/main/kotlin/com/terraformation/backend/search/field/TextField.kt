package com.terraformation.backend.search.field

import com.terraformation.backend.db.collation
import com.terraformation.backend.db.likeFuzzy
import com.terraformation.backend.db.unaccent
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.util.removeDiacritics
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * Search field for arbitrary text values. This does not differentiate between short values such as
 * a person's name and longer values such as notes.
 */
class TextField(
    override val fieldName: String,
    override val databaseField: Field<String?>,
    override val table: SearchTable,
) : SingleColumnSearchField<String>() {
  override val localize: Boolean
    get() = false

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Fuzzy, SearchFilterType.PhraseMatch)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val normalizedValues =
        fieldNode.values.map { it?.lowercase(currentLocale())?.removeDiacritics() }
    val nonNullValues = normalizedValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(if (fieldNode.values.any { it == null }) databaseField.isNull else null)
                  .plus(nonNullValues.map { DSL.lower(databaseField).unaccent().contains(it) })
          )
      SearchFilterType.ExactOrFuzzy,
      SearchFilterType.Fuzzy ->
          DSL.or(
              normalizedValues.map { value ->
                if (value != null) {
                  databaseField.unaccent().likeFuzzy(value)
                } else {
                  databaseField.isNull
                }
              }
          )
      SearchFilterType.PhraseMatch ->
          DSL.or(
              listOfNotNull(if (fieldNode.values.any { it == null }) databaseField.isNull else null)
                  .plus(phaseMatchCondition(nonNullValues))
          )
      SearchFilterType.Range ->
          throw IllegalArgumentException("Range search not supported for text fields")
    }
  }

  override val orderByField: Field<*>
    get() = databaseField.collate(currentLocale().collation)

  // Text fields are always raw.
  override fun raw(): SearchField? = null
}
