package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

/** Base class for fields that map to a single database column. */
abstract class SingleColumnSearchField<T : Any> : SearchField {
  abstract val databaseField: Field<T?>

  abstract fun getCondition(fieldNode: FieldNode): Condition?

  override val selectFields: List<Field<*>>
    get() = listOf(databaseField)

  override val orderByField: Field<*>
    get() = databaseField

  override fun getConditions(fieldNode: FieldNode) = listOfNotNull(getCondition(fieldNode))

  override fun computeValue(record: Record) = record.get(databaseField)?.toString()

  override fun toString() = fieldName

  override fun hashCode() = fieldName.hashCode()

  override fun equals(other: Any?): Boolean {
    return other != null &&
        other is SingleColumnSearchField<*> &&
        other.javaClass == javaClass &&
        other.fieldName == fieldName &&
        other.databaseField == databaseField &&
        other.table == table
  }

  protected fun phaseMatchCondition(values: List<String>): Condition =
      DSL.or(
          values.flatMap {
            listOf(
                databaseField.likeIgnoreCase(it), // Exact match with phrase
                databaseField.likeIgnoreCase("% $it %"), // phrase in the middle
                databaseField.likeIgnoreCase("$it %"), // phrase as a prefix
                databaseField.likeIgnoreCase("% $it"), // phrase as a suffix
            )
          }
      )

  /**
   * Returns a Condition for a range query on a field with a data type that is compatible with the
   * SQL BETWEEN operator.
   */
  protected fun rangeCondition(values: List<T?>): Condition {
    if (values.size != 2 || values[0] == null && values[1] == null) {
      throw IllegalArgumentException("Range search must have two non-null values")
    }

    return if (values[0] != null && values[1] != null) {
      databaseField.between(values[0], values[1])
    } else if (values[0] == null) {
      databaseField.le(values[1])
    } else {
      databaseField.ge(values[0])
    }
  }
}
