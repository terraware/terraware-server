package com.terraformation.backend.search

import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import java.util.Objects
import org.jooq.Condition
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.SortField
import org.jooq.Table

/**
 * A [SearchTable] that wraps an underlying table in an alias. A query can include the same search
 * table more than once, e.g., if it asks for both `createdBy.firstName` and `modifiedBy.firstName`
 * which would both reference the `users` table; each occurrence gets a distinct alias.
 */
class AliasedSearchTable(val baseTable: SearchTable, val alias: String) : SearchTable() {
  override val fromTable: Table<out Record> by lazy { baseTable.fromTable.`as`(alias) }

  override val fields: List<SearchField> by lazy { baseTable.fields.map { it.withTable(this) } }

  override val sublists: List<SublistField> by lazy {
    baseTable.sublists.mapIndexed { index, field ->
      field.copy(searchTable = field.searchTable.withAlias("${alias}_s$index"))
    }
  }

  override fun withAlias(alias: String): AliasedSearchTable {
    // Don't nest aliases; return an alias of the underlying table.
    return baseTable.withAlias(alias)
  }

  override val primaryKey: Field<out Any?> by lazy {
    fromTable.field(baseTable.primaryKey)
        ?: throw IllegalStateException(
            "BUG! Primary key ${baseTable.primaryKey} not found in $fromTable; try calling " +
                "primaryKeyFields instead"
        )
  }

  override val primaryKeyFields: List<Field<*>> by lazy {
    baseTable.primaryKeyFields.map { fromTable.column(it) }
  }

  override val defaultOrderFields: List<OrderField<*>> by lazy {
    baseTable.defaultOrderFields.map { orderField ->
      when (orderField) {
        // Sort specifications such as SOME_COLUMN.desc() have to be taken apart and reassembled so
        // the column can be resolved against this table's alias without losing the sort direction.
        is SortField<*> -> fromTable.column(orderField.`$field`()).sort(orderField.order)
        is Field<*> -> fromTable.column(orderField)
        else ->
            throw IllegalStateException("BUG! Can't alias order field $orderField of $baseTable")
      }
    }
  }

  /** Field descriptions are looked up by the underlying table's name, not the alias. */
  override val name: String
    get() = baseTable.name

  override val inheritsVisibilityFrom: SearchTable?
    get() = baseTable.inheritsVisibilityFrom

  override fun conditionForVisibility(table: Table<*>): Condition? =
      baseTable.conditionForVisibility(table)

  override fun <T : Record> joinForVisibility(
      query: SelectJoinStep<T>,
      table: Table<*>,
  ): SelectJoinStep<T> = baseTable.joinForVisibility(query, table)

  override fun equals(other: Any?): Boolean {
    return other is AliasedSearchTable && other.baseTable == baseTable && other.alias == alias
  }

  override fun hashCode() = Objects.hash(baseTable, alias)
}
