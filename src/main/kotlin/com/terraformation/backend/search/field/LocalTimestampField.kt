package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns that have local timestamps. */
class LocalTimestampField(
    override val fieldName: String,
    override val databaseField: TableField<*, LocalDateTime?>,
    override val table: SearchTable,
) : SingleColumnSearchField<LocalDateTime>() {
  override val localize: Boolean
    get() = false

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val dateTimeValues =
        try {
          fieldNode.values.map { if (it != null) LocalDateTime.parse(it) else null }
        } catch (e: DateTimeParseException) {
          throw IllegalArgumentException(
              "Timestamps must be in ISO-8601 format (example: 2021-05-28T18:45:30)"
          )
        }
    val nonNullLocalDateTime = dateTimeValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (nonNullLocalDateTime.isNotEmpty()) databaseField.`in`(nonNullLocalDateTime)
                  else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null,
              )
          )
      SearchFilterType.Partial ->
          throw IllegalArgumentException("Partial search not supported for timestamps")
      SearchFilterType.PartialOrFuzzy,
      SearchFilterType.Fuzzy ->
          throw IllegalArgumentException("Fuzzy search not supported for timestamps")
      SearchFilterType.PhraseMatch ->
          throw IllegalArgumentException("Phrase match not supported for timestamps")
      SearchFilterType.Range -> rangeCondition(dateTimeValues)
    }
  }

  // Timestamp values are always machine-readable.
  override fun raw(): SearchField? = null
}
