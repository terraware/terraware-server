package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns that have full timestamps. */
class TimestampField(
    override val fieldName: String,
    override val databaseField: TableField<*, Instant?>,
    override val table: SearchTable,
) : SingleColumnSearchField<Instant>() {
  override val localize: Boolean
    get() = false

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val instantValues =
        try {
          fieldNode.values.map { if (it != null) Instant.parse(it) else null }
        } catch (e: DateTimeParseException) {
          throw IllegalArgumentException(
              "Timestamps must be in RFC 3339 format (example: 2021-05-28T18:45:30Z)"
          )
        }
    val nonNullInstants = instantValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (nonNullInstants.isNotEmpty()) databaseField.`in`(nonNullInstants) else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null,
              )
          )
      SearchFilterType.ExactOrFuzzy,
      SearchFilterType.Fuzzy ->
          throw IllegalArgumentException("Fuzzy search not supported for timestamps")
      SearchFilterType.PhraseMatch ->
          throw IllegalArgumentException("Phrase match not supported for timestamps")
      SearchFilterType.Range -> rangeCondition(instantValues)
    }
  }

  // Timestamp values are always machine-readable.
  override fun raw(): SearchField? = null
}
