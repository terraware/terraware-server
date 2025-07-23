package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/** Search field for ID columns that use wrapper types. */
abstract class IdField<T : Any>(
    override val fieldName: String,
    override val databaseField: Field<T?>,
    override val table: SearchTable,
) : SingleColumnSearchField<T>() {
  override val localize: Boolean
    get() = false

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val allValues = getAllFieldNodeValues(fieldNode)
    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (allValues.isNotEmpty()) databaseField.`in`(allValues) else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      SearchFilterType.ExactOrFuzzy,
      SearchFilterType.Fuzzy -> throw RuntimeException("Fuzzy search not supported for IDs")
      SearchFilterType.PhraseMatch -> throw RuntimeException("Phrase match not supported for IDs")
      SearchFilterType.Range -> throw RuntimeException("Range search not supported for IDs")
    }
  }

  abstract fun getAllFieldNodeValues(fieldNode: FieldNode): List<T?>

  // IDs are already machine-readable.
  override fun raw(): SearchField? = null
}
