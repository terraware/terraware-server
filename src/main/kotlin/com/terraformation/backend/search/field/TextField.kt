package com.terraformation.backend.search.field

import com.terraformation.backend.db.collation
import com.terraformation.backend.db.likeFuzzy
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.*
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
    override val nullable: Boolean = true,
) : SingleColumnSearchField<String>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Fuzzy)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val nonNullValues = fieldNode.values.filterNotNull().map { it.lowercase() }
    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (nonNullValues.isNotEmpty()) {
                    DSL.lower(databaseField).`in`(nonNullValues)
                  } else {
                    null
                  },
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      SearchFilterType.Fuzzy ->
          DSL.or(
              fieldNode.values.map { value ->
                if (value != null) {
                  databaseField.likeFuzzy(value)
                } else {
                  databaseField.isNull
                }
              })
      SearchFilterType.Range ->
          throw IllegalArgumentException("Range search not supported for text fields")
    }
  }

  override val orderByField: Field<*>
    get() = databaseField.collate(currentLocale().collation)
}
