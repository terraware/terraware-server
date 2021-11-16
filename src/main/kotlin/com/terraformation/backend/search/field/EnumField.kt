package com.terraformation.backend.search.field

import com.terraformation.backend.db.EnumFromReferenceTable
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.*
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Search field for columns that refer to reference tables that get compiled to Kotlin enum classes
 * during code generation. Because the contents of these tables are known at compile time, we don't
 * need to join with them and can instead directly include their IDs in our generated SQL.
 */
class EnumField<E : Enum<E>, T : EnumFromReferenceTable<E>>(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, T?>,
    override val table: SearchTable,
    private val enumClass: Class<T>,
    override val nullable: Boolean = true
) : SingleColumnSearchField<T>() {
  private val byDisplayName: Map<String, T> =
      enumClass.enumConstants!!.associateBy { it.displayName }

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)
  override val possibleValues = enumClass.enumConstants!!.map { it.displayName }

  override fun getCondition(fieldNode: FieldNode): Condition {
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

  override val orderByField: Field<*>
    get() {
      val displayNames = enumClass.enumConstants!!.associateWith { it.displayName }
      return DSL.case_(databaseField).mapValues(displayNames)
    }

  override fun computeValue(record: Record) = record[databaseField]?.displayName
}
