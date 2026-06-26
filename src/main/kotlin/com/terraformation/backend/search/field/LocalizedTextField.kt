package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.util.removeDiacritics
import java.text.Collator
import java.text.MessageFormat
import java.util.EnumSet
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * Search field for values that are mapped to localized strings but are not represented as enums.
 */
class LocalizedTextField<T : Any>(
    override val fieldName: String,
    /** The field that has the name of the string to look up in the resource bundle. */
    override val databaseField: Field<T?>,
    private val resourceBundleName: String,
    private val prefix: String?,
    override val table: SearchTable,
    override val localize: Boolean = true,
    override val exportable: Boolean = true,
) : SingleColumnSearchField<T>() {
  /**
   * Maps lower-case diacritic-free localized strings to their corresponding database field values.
   * The interior Map is sorted alphabetically by localized string.
   */
  private val fieldValuesByLocalizedString = ConcurrentHashMap<Locale, Map<String, String>>()

  /** Maps the database field values to their corresponding localized strings. */
  private val localizedStringsByFieldValue = ConcurrentHashMap<Locale, Map<String, String>>()

  /**  */
  private val orderByFields = ConcurrentHashMap<Locale, Field<Int>>()

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override fun getCondition(fieldNode: FieldNode): Condition {
    val locale = currentLocale()
    val valuesMap = getFieldValuesByLocalizedStringMap()
    val normalizedFieldValues = fieldNode.values.map { it?.lowercase(locale)?.removeDiacritics() }
    val nonNullFieldValues = normalizedFieldValues.filterNotNull().map { valuesMap[it] }

    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (nonNullFieldValues.isNotEmpty()) {
                    databaseField.cast(SQLDataType.VARCHAR).`in`(nonNullFieldValues)
                  } else {
                    null
                  },
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null,
              )
          )
      SearchFilterType.Partial ->
          throw IllegalArgumentException("Partial search not supported for localized text fields")
      SearchFilterType.PartialOrFuzzy,
      SearchFilterType.Fuzzy ->
          throw IllegalArgumentException("Fuzzy search not supported for localized text fields")
      SearchFilterType.PhraseMatch ->
          throw IllegalArgumentException("Phrase match not supported for localized text fields")
      SearchFilterType.Range ->
          throw IllegalArgumentException("Range search not supported for localized text fields")
    }
  }

  override fun computeValue(record: Record): String? {
    return record[databaseField]?.let { getLocalizedString("$it") }
  }

  override val possibleValues: List<String>
    get() = getFieldValuesByLocalizedStringMap().keys.toList()

  /**
   * Returns an expression that converts the database field to an integer sort position. We need to
   * construct this in the application code because the database doesn't know the localized strings.
   */
  override val orderByField: Field<*>
    get() {
      return orderByFields.getOrPut(currentLocale()) {
        val valuesMap = getFieldValuesByLocalizedStringMap()
        val fieldValuesToPosition: Map<String?, Int> =
            valuesMap.entries.mapIndexed { index, (_, fieldValue) -> fieldValue to index }.toMap()

        return DSL.case_(databaseField.cast(SQLDataType.VARCHAR))
            .mapValues(fieldValuesToPosition)
            .else_(valuesMap.size)
      }
    }

  // Localized text fields are always localized and have no raw values.
  override fun raw(): SearchField? = null

  private fun getLocalizedString(databaseValue: String): String {
    val locale = currentLocale()
    val stringsForLocale =
        localizedStringsByFieldValue.getOrPut(locale) {
          val bundle = ResourceBundle.getBundle(resourceBundleName, locale)
          bundle.keySet().associateWith { MessageFormat.format(bundle.getString(it)) }
        }

    return stringsForLocale[stringKey(databaseValue)]
        ?: throw IllegalStateException("No localized string for $databaseValue in $locale")
  }

  private fun getFieldValuesByLocalizedStringMap(): Map<String, String> {
    val locale = currentLocale()
    return fieldValuesByLocalizedString.getOrPut(locale) {
      val bundle = ResourceBundle.getBundle(resourceBundleName, locale)
      val map =
          bundle
              .keySet()
              .filter { key -> prefix == null || key.startsWith(prefix) }
              .associate { key ->
                val fieldValue = prefix?.let { key.substringAfter(prefix) } ?: key
                val localizedText =
                    MessageFormat.format(bundle.getString(key)).lowercase(locale).removeDiacritics()
                localizedText to fieldValue
              }

      map.keys.sortedWith(Collator.getInstance(locale)).associateWith { map[it]!! }
    }
  }

  private fun stringKey(baseKey: String) = prefix?.let { "$prefix$baseKey" } ?: baseKey
}
