package com.terraformation.backend.search.field

import com.terraformation.backend.db.collation
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.net.URI
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/** Search field for urls. */
class UriField(
    override val fieldName: String,
    override val databaseField: Field<URI?>,
    override val table: SearchTable,
    override val nullable: Boolean = true,
) : SingleColumnSearchField<URI>() {
  override val localize: Boolean
    get() = false

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.PhraseMatch)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val valuesRegex = fieldNode.values.map { "$it" }
    return when (fieldNode.type) {
      SearchFilterType.PhraseMatch,
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(if (fieldNode.values.any { it == null }) databaseField.isNull else null)
                  .plus(valuesRegex.map { databaseField.likeRegex(it) }))
      SearchFilterType.ExactOrFuzzy,
      SearchFilterType.Fuzzy ->
          throw IllegalArgumentException("Fuzzy search is not supported for URI fields")
      SearchFilterType.Range ->
          throw IllegalArgumentException("Range search is not supported for URI fields")
    }
  }

  override val orderByField: Field<*>
    get() = databaseField.collate(currentLocale().collation)

  // Text fields are always raw.
  override fun raw(): SearchField? = null
}
