package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Search field that represents the age of an underlying date column at some level of granularity.
 * The age depends on the current time since it's the difference between the current time and the
 * value of the date column.
 *
 * Ages are computed by comparing partial dates, not by subtracting dates and calculating the size
 * of the difference. That is, all the dates in March are considered 1 month older than all the
 * dates in April, regardless of where in the month the dates are. Similarly, all the dates in 2021
 * are considered 1 year older than all the dates in 2022. This can produce unintuitive results if
 * the dates are close together (January 31 is 1 month older than February 1) but is predictable and
 * avoids unintuitive results caused by months and years being variable-length.
 */
class AgeField(
    override val fieldName: String,
    override val databaseField: TableField<*, LocalDate?>,
    override val table: SearchTable,
    override val localize: Boolean = true,
    override val exportable: Boolean = true,
    private val granularity: AgeGranularity,
    private val clock: Clock,
) : SingleColumnSearchField<LocalDate>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val now = LocalDate.now(clock)
    val dateRanges = fieldNode.values.map { dateRangeOrNull(it, now) }

    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              dateRanges.map { range ->
                if (range != null) {
                  // An "exact" match on an age needs to be translated to a range of dates, since
                  // any date in a given month should be treated as having the same age.
                  databaseField.between(range.first, range.second)
                } else {
                  databaseField.isNull
                }
              }
          )
      SearchFilterType.ExactOrFuzzy,
      SearchFilterType.Fuzzy ->
          throw IllegalArgumentException("Fuzzy search not supported for dates")
      SearchFilterType.PhraseMatch ->
          throw IllegalArgumentException("Phrase match not supported for dates")
      SearchFilterType.Range -> {
        if (dateRanges.size != 2) {
          throw IllegalArgumentException("Range search must have exactly two values")
        }

        // Arguments are [min age, max age] which means the second argument is the start of the
        // date range since ages are ordered the opposite of dates.
        val start = dateRanges[1]?.first
        val end = dateRanges[0]?.second

        rangeCondition(listOf(start, end))
      }
    }
  }

  override fun computeValue(record: Record): String? {
    val date = record[databaseField] ?: return null
    val now = LocalDate.now(clock)

    val difference = granularity.difference(date, now)

    return if (localize) {
      NumberFormat.getIntegerInstance(currentLocale()).format(difference)
    } else {
      "$difference"
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      AgeField(rawFieldName(), databaseField, table, false, false, granularity, clock)
    } else {
      null
    }
  }

  /**
   * Returns the start and end of the range of dates that correspond to the given age, or null if
   * the age is null.
   */
  private fun dateRangeOrNull(ageString: String?, now: LocalDate): Pair<LocalDate, LocalDate>? {
    val age =
        when {
          ageString == null -> return null
          localize -> NumberFormat.getIntegerInstance(currentLocale()).parse(ageString).toInt()
          else -> ageString.toInt()
        }

    if (age < 0) {
      throw IllegalArgumentException("Age must be non-negative")
    }

    return granularity.dateRange(age, now)
  }

  /**
   * Returns an expression to use for sorting in order of age. Rather than sorting by the calculated
   * age values, we sort by the underlying date, but in the opposite order of the actual dates
   * (since lesser dates have greater ages).
   */
  override val orderByField: Field<*>
    get() = DSL.localDateDiff(LocalDate.now(clock), databaseField)

  /** Performs computations on dates to turn them into ages at different granularities. */
  sealed interface AgeGranularity {
    /** Truncates a date to this granularity. */
    fun truncate(date: LocalDate): LocalDate

    /** Returns the difference between two dates in units of this granularity. */
    fun difference(startDate: LocalDate, endDate: LocalDate): Int

    /** Returns the start and end of the range of dates that correspond to the given age. */
    fun dateRange(age: Int, now: LocalDate): Pair<LocalDate, LocalDate>
  }

  object YearGranularity : AgeGranularity {
    override fun truncate(date: LocalDate): LocalDate {
      return LocalDate.of(date.year, 1, 1)
    }

    override fun difference(startDate: LocalDate, endDate: LocalDate): Int {
      return endDate.year - startDate.year
    }

    override fun dateRange(age: Int, now: LocalDate): Pair<LocalDate, LocalDate> {
      val start = truncate(now).minusYears(age.toLong())
      val end = start.plusYears(1).minusDays(1)

      return start to end
    }
  }

  object MonthGranularity : AgeGranularity {
    override fun truncate(date: LocalDate): LocalDate {
      return LocalDate.of(date.year, date.month, 1)
    }

    override fun difference(startDate: LocalDate, endDate: LocalDate): Int {
      return ((endDate.year - startDate.year) * 12) + (endDate.monthValue - startDate.monthValue)
    }

    override fun dateRange(age: Int, now: LocalDate): Pair<LocalDate, LocalDate> {
      val start = truncate(now).minusMonths(age.toLong())
      val end = start.plusMonths(1).minusDays(1)

      return start to end
    }
  }
}
