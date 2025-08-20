package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

/** Search field for columns with boolean values. */
class BooleanField(
    override val fieldName: String,
    override val databaseField: Field<Boolean?>,
    override val table: SearchTable,
    override val localize: Boolean = true,
    override val exportable: Boolean = true,
) : SingleColumnSearchField<Boolean>() {
  private val trueStrings = ConcurrentHashMap<Locale, String>()
  private val falseStrings = ConcurrentHashMap<Locale, String>()

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val booleanValues =
        fieldNode.values.map { stringValue ->
          when (stringValue) {
            getString(true) -> true
            getString(false) -> false
            null -> null
            else -> throw IllegalArgumentException("Unrecognized value $stringValue")
          }
        }
    val nonNullValues = booleanValues.filterNotNull()

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
          throw RuntimeException("Fuzzy search not supported for boolean fields")
      SearchFilterType.PhraseMatch ->
          throw IllegalArgumentException("Phrase match not supported for boolean fields")
      SearchFilterType.Range ->
          throw RuntimeException("Range search not supported for boolean fields")
    }
  }

  override fun computeValue(record: Record) = record[databaseField]?.let { getString(it) }

  override val possibleValues: List<String>
    get() = listOf(getString(true), getString(false))

  override fun raw(): SearchField? {
    return if (localize) {
      BooleanField(rawFieldName(), databaseField, table, false, false)
    } else {
      null
    }
  }

  private fun getString(value: Boolean): String {
    return if (localize) {
      val locale = currentLocale()
      val stringMap = if (value) trueStrings else falseStrings

      stringMap.getOrPut(locale) {
        ResourceBundle.getBundle("i18n.Messages", locale).getString("boolean.$value")
      }
    } else {
      "$value"
    }
  }
}
