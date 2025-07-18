package com.terraformation.backend.search.field

import com.terraformation.backend.db.LocalizableEnum
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.text.Collator
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Search field for columns that refer to reference tables that get compiled to Kotlin enum classes
 * during code generation. Because the contents of these tables are known at compile time, we don't
 * need to join with them and can instead directly include their IDs in our generated SQL.
 */
class EnumField<E : Enum<E>, T : LocalizableEnum<E>>(
    override val fieldName: String,
    override val databaseField: Field<T?>,
    override val table: SearchTable,
    private val enumClass: Class<T>,
    override val localize: Boolean = true,
    override val exportable: Boolean = true,
) : SingleColumnSearchField<T>() {
  private val byLocalizedDisplayName = ConcurrentHashMap<Locale, Map<String, T>>()
  private val orderByFields = ConcurrentHashMap<Locale, Field<Int>>()

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override val possibleValues
    get() = enumClass.enumConstants!!.map { it.toSearchValue() }

  override fun getCondition(fieldNode: FieldNode): Condition {
    val byDisplayName: Map<String, T> =
        byLocalizedDisplayName.getOrPut(currentLocale()) {
          enumClass.enumConstants!!.associateBy { it.toSearchValue() }
        }

    if (fieldNode.type != SearchFilterType.Exact) {
      throw IllegalArgumentException("$fieldName only supports exact searches")
    }

    val enumInstances =
        fieldNode.values.filterNotNull().map {
          byDisplayName[it]
              ?: throw IllegalArgumentException("Value $it not recognized for $fieldName")
        }

    return DSL.or(
        listOfNotNull(
            if (enumInstances.isNotEmpty()) databaseField.`in`(enumInstances) else null,
            if (fieldNode.values.any { it == null }) databaseField.isNull else null))
  }

  /**
   * Returns an expression that evaluates to the ordinal position of each enum value based on its
   * display name in the current locale, folded to lower case and sorted using the locale's
   * collation rules.
   */
  override val orderByField: Field<Int>
    get() {
      val locale = currentLocale()
      return orderByFields.getOrPut(locale) {
        val collator = Collator.getInstance(locale)
        val toLowerCaseDisplayName: (T) -> String = { it.toSearchValue().lowercase(locale) }

        val valueToPosition =
            enumClass.enumConstants
                .sortedWith(compareBy(collator, toLowerCaseDisplayName))
                .mapIndexed { index, value -> value to index }
                .toMap()

        return DSL.case_(databaseField).mapValues(valueToPosition)
      }
    }

  override fun computeValue(record: Record) = record[databaseField]?.toSearchValue()

  override fun raw(): SearchField? {
    return if (localize) {
      EnumField(rawFieldName(), databaseField, table, enumClass, false, false)
    } else {
      null
    }
  }

  private fun T.toSearchValue(): String {
    return if (localize) {
      getDisplayName(currentLocale())
    } else {
      jsonValue
    }
  }
}
