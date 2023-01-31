package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.*
import org.jooq.Condition
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns that have dates without times or timezones. */
class DateField(
    override val fieldName: String,
    override val databaseField: TableField<*, LocalDate?>,
    override val table: SearchTable,
    override val nullable: Boolean
) : SingleColumnSearchField<LocalDate>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val dateValues =
        try {
          fieldNode.values.map { if (it != null) LocalDate.parse(it) else null }
        } catch (e: DateTimeParseException) {
          throw IllegalArgumentException("Dates must be in YYYY-MM-DD format")
        }
    val nonNullDates = dateValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (nonNullDates.isNotEmpty()) databaseField.`in`(nonNullDates) else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null,
              ))
      SearchFilterType.Fuzzy ->
          throw IllegalArgumentException("Fuzzy search not supported for dates")
      SearchFilterType.Range -> rangeCondition(dateValues)
    }
  }
}
