package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.Select
import org.jooq.impl.DSL

/**
 * Search field for columns that indicate the existence of something. This is typically used to
 * check for the existence of a foreign key reference to the table this field belongs to.
 */
class ExistsField(
    override val fieldName: String,
    override val displayName: String,
    private val parentIdField: Field<*>,
    selectQuery: Select<*>,
    override val table: SearchTable,
) : SearchField {
  private val existsCondition = DSL.exists(selectQuery)
  private val existsField = DSL.field(existsCondition)

  override val nullable: Boolean
    get() = false
  override val orderByField: Field<*>
    get() = existsField
  override val selectFields: List<Field<*>> = listOf(parentIdField, existsField)
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override fun getConditions(fieldNode: FieldNode): List<Condition> {
    val booleanValues =
        fieldNode.values.map { stringValue ->
          when (stringValue?.lowercase()) {
            "true" -> true
            "false" -> false
            null -> throw IllegalArgumentException("Field is not nullable")
            else -> throw IllegalArgumentException("Unrecognized value $stringValue")
          }
        }

    val wantExists = booleanValues.any { it }
    val wantNotExists = booleanValues.any { !it }

    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        listOf(
            when {
              // "Exists or not" is a no-op
              wantExists && wantNotExists -> parentIdField.isNotNull
              wantExists -> existsCondition.and(parentIdField.isNotNull)
              wantNotExists -> DSL.not(existsCondition).and(parentIdField.isNotNull)
              else -> DSL.falseCondition()
            })
      }
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for boolean fields")
      SearchFilterType.Range ->
          throw RuntimeException("Range search not supported for boolean fields")
    }
  }

  override fun computeValue(record: Record): String? {
    return if (record[parentIdField] != null) {
      when (record[existsField]) {
        true -> "true"
        false -> "false"
        null -> null
      }
    } else {
      null
    }
  }
}
