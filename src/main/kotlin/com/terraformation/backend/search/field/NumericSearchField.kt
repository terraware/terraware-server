package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Search field superclass for columns with numeric values. Numeric values can always be searched
 * for exact values or ranges, but never with fuzzy matching.
 */
abstract class NumericSearchField<T : Number>(
    override val fieldName: String,
    override val databaseField: Field<T?>,
    override val table: SearchTable,
    override val localize: Boolean,
    override val exportable: Boolean,
) : SingleColumnSearchField<T>() {
  companion object {
    const val MAXIMUM_FRACTION_DIGITS = 5
  }

  private val numberFormats = ConcurrentHashMap<Locale, NumberFormat>()

  /** Returns an appropriate [NumberFormat] for the numeric type in the current locale. */
  abstract fun makeNumberFormat(): NumberFormat

  /** Parses a string value into whatever numeric type this column uses. */
  abstract fun fromString(value: String): T

  protected val numberFormat: NumberFormat
    get() = numberFormats.getOrPut(currentLocale(), this::makeNumberFormat)

  override val supportedFilterTypes: Set<SearchFilterType> =
      EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun getCondition(fieldNode: FieldNode): Condition? {
    val numericValues = fieldNode.values.map { if (it != null) fromString(it) else null }
    val nonNullValues = numericValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        DSL.or(
            listOfNotNull(
                if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                if (fieldNode.values.any { it == null }) databaseField.isNull else null,
            )
        )
      }
      SearchFilterType.ExactOrFuzzy,
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for numeric fields")
      SearchFilterType.PhraseMatch ->
          throw RuntimeException("Phrase match not supported for numeric fields")
      SearchFilterType.Range -> rangeCondition(numericValues)
    }
  }

  override fun computeValue(record: Record) =
      record[databaseField]?.let { value ->
        if (localize) numberFormat.format(value) else value.toString()
      }
}
