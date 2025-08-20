package com.terraformation.backend.search.field

import com.terraformation.backend.db.EnumFromReferenceTable
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Search field for columns that refer to reference tables that get compiled to Kotlin enum classes
 * during code generation. Because the contents of these tables are known at compile time, we don't
 * need to join with them and can instead directly include their IDs in our generated SQL.
 *
 * This is used for values that don't ever need to be localized, which will generally be values that
 * are only for internal use.
 */
class NonLocalizableEnumField<E : Enum<E>, T : EnumFromReferenceTable<*, E>>(
    override val fieldName: String,
    override val databaseField: TableField<*, T?>,
    override val table: SearchTable,
    private val enumClass: Class<T>,
    override val exportable: Boolean = true,
) : SingleColumnSearchField<T>() {
  private val byName = enumClass.enumConstants!!.associateBy { it.jsonValue }

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override val possibleValues = byName.keys.toList()

  override fun getCondition(fieldNode: FieldNode): Condition {
    if (fieldNode.type != SearchFilterType.Exact) {
      throw IllegalArgumentException("$fieldName only supports exact searches")
    }

    val enumInstances =
        fieldNode.values.filterNotNull().map {
          byName[it] ?: throw IllegalArgumentException("Value $it not recognized for $fieldName")
        }

    return DSL.or(
        listOfNotNull(
            if (enumInstances.isNotEmpty()) databaseField.`in`(enumInstances) else null,
            if (fieldNode.values.any { it == null }) databaseField.isNull else null,
        )
    )
  }

  /**
   * Returns an expression that evaluates to the ordinal position of each enum value based on its
   * display name.
   */
  override val orderByField: Field<Int> by lazy {
    val valueToPosition =
        enumClass.enumConstants
            .sortedBy { it.jsonValue.lowercase() }
            .mapIndexed { index, value -> value to index }
            .toMap()

    DSL.case_(databaseField).mapValues(valueToPosition)
  }

  override fun computeValue(record: Record) = record[databaseField]?.jsonValue

  override fun raw(): SearchField? = null
}
