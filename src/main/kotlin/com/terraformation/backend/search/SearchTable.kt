package com.terraformation.backend.search

import com.terraformation.backend.seedbank.search.SearchService
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table

/**
 * Defines a table whose columns can be declared as [SearchField] s. The methods here are used in
 * [SearchService] when it dynamically constructs SQL queries based on a search request from a
 * client.
 */
interface SearchTable {
  /** The jOOQ Table object for the table in question. */
  val fromTable: Table<out Record>

  /**
   * Adds a LEFT JOIN clause to a query to connect this table to the main table. The implementation
   * can assume that the main table is already present in the SELECT statement.
   */
  fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T>

  /**
   * Adds a LEFT JOIN clause to a query to connect this table to any other tables required to filter
   * out values the user doesn't have permission to see.
   *
   * This is only used when querying all the values of a table; for accession searches, permissions
   * are checked on the accession.
   *
   * The default no-op implementation will work for any tables that have the required information
   * already, e.g., if a table has a facility ID column, there's no need to join with another table
   * to get a facility ID.
   */
  fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> = query

  /**
   * Returns a condition that restricts this table's values to ones the user has permission to see.
   * If the table's values are visible to all users, returns null.
   *
   * This method can safely assume that [joinForPermissions] was called, so any tables added there
   * are available for use in the condition.
   */
  fun conditionForPermissions(): Condition?

  /**
   * An intermediate table that needs to be joined with this one in order to connect this table to
   * the accessions table. This supports multi-step chains of foreign keys. For example, if table
   * `foo` has a foreign key column `accession_id` and table `bar` has a foreign key `foo_id`, a
   * query that wants to get a column from `bar` would need to also join with `foo`. In that case,
   * this method would return the [SearchTable] for `foo`.
   *
   * This should be null (the default) for children that can be directly joined with the accessions
   * table.
   */
  val parent: SearchTable?
    get() = null

  /**
   * Returns a condition to add to the `WHERE` clause of a multiset subquery to correlate it with
   * the current row from the parent table.
   */
  fun conditionForMultiset(): Condition?

  /**
   * Returns the default fields to sort on. These are always included when querying the table; if
   * there are user-supplied sort criteria, these come at the end. This allows us to return stable
   * query results if the user-requested sort fields have duplicate values.
   */
  val defaultOrderFields: List<OrderField<*>>
    get() =
        fromTable.primaryKey?.fields
            ?: throw IllegalStateException("BUG! No primary key fields found for $fromTable")
}
