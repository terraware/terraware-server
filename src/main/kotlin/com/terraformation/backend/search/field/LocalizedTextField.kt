package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.text.Collator
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Search field for values that are mapped to localized strings but are not represented as enums.
 */
class LocalizedTextField(
    override val fieldName: String,
    /** The field that has the name of the string to look up in the resource bundle. */
    override val databaseField: Field<String?>,
    private val resourceBundleName: String,
    override val table: SearchTable,
    override val nullable: Boolean = true,
) : SingleColumnSearchField<String>() {
  /**
   * Maps the lower-case localized strings to their corresponding database field values. The
   * interior Map is sorted alphabetically by localized string.
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
    val nonNullFieldValues =
        fieldNode.values.filterNotNull().map { valuesMap[it.lowercase(locale)] }

    return when (fieldNode.type) {
      SearchFilterType.Exact ->
          DSL.or(
              listOfNotNull(
                  if (nonNullFieldValues.isNotEmpty()) {
                    databaseField.`in`(nonNullFieldValues)
                  } else {
                    null
                  },
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      SearchFilterType.Fuzzy ->
          throw IllegalArgumentException("Fuzzy search not supported for localized text fields")
      SearchFilterType.Range ->
          throw IllegalArgumentException("Range search not supported for localized text fields")
    }
  }

  override fun computeValue(record: Record): String? {
    return record[databaseField]?.let { getLocalizedString(it) }
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

        return DSL.case_(databaseField).mapValues(fieldValuesToPosition).else_(valuesMap.size)
      }
    }

  private fun getLocalizedString(databaseValue: String): String {
    val locale = currentLocale()
    val stringsForLocale =
        localizedStringsByFieldValue.getOrPut(locale) {
          val bundle = ResourceBundle.getBundle(resourceBundleName, locale)
          bundle.keySet().associateWith { bundle.getString(it) }
        }

    return stringsForLocale[databaseValue]
        ?: throw IllegalStateException("No localized string for $databaseValue in $locale")
  }

  private fun getFieldValuesByLocalizedStringMap(): Map<String, String> {
    val locale = currentLocale()
    return fieldValuesByLocalizedString.getOrPut(locale) {
      val bundle = ResourceBundle.getBundle(resourceBundleName, locale)
      val map = bundle.keySet().associateBy { bundle.getString(it).lowercase(locale) }
      map.keys.sortedWith(Collator.getInstance(locale)).associateWith { map[it]!! }
    }
  }
}
