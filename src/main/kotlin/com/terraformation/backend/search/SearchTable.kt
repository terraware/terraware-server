package com.terraformation.backend.search

import com.terraformation.backend.db.EnumFromReferenceTable
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.search.field.BigDecimalField
import com.terraformation.backend.search.field.DateField
import com.terraformation.backend.search.field.EnumField
import com.terraformation.backend.search.field.GramsField
import com.terraformation.backend.search.field.IdWrapperField
import com.terraformation.backend.search.field.IntegerField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.TextField
import com.terraformation.backend.search.field.TimestampField
import com.terraformation.backend.search.field.UpperCaseTextField
import com.terraformation.backend.seedbank.search.SearchService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.jooq.Condition
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField

/**
 * Defines a table whose columns can be declared as [SearchField] s. The methods here are used in
 * [SearchService] when it dynamically constructs SQL queries based on a search request from a
 * client.
 */
abstract class SearchTable(private val fuzzySearchOperators: FuzzySearchOperators) {
  /** The jOOQ Table object for the table in question. */
  abstract val fromTable: Table<out Record>

  /**
   * Adds a LEFT JOIN clause to a query to connect this table to the main table. The implementation
   * can assume that the main table is already present in the SELECT statement.
   */
  abstract fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T>

  /**
   * Adds a LEFT JOIN clause to a query to connect this table to another table to calculate whether
   * the user is allowed to see a row in this table.
   *
   * This must join to the same table referenced by [inheritsPermissionsFrom].
   *
   * The default no-op implementation will work for any tables that have the required information
   * already, e.g., if a table has a facility ID column, there's no need to join with another table
   * to get a facility ID. The default implementation is only valid if [inheritsPermissionsFrom]
   * returns null.
   */
  open fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
    if (inheritsPermissionsFrom == null) {
      return query
    } else {
      throw IllegalStateException(
          "BUG! Must override joinForPermissions if permissions are inherited from another table.")
    }
  }

  /**
   * Returns a condition that restricts this table's values to ones the user has permission to see.
   *
   * This method can safely assume that [joinForPermissions] was called, so any tables added there
   * are available for use in the condition.
   *
   * If this is null, [inheritsPermissionsFrom] must be non-null.
   */
  abstract fun conditionForPermissions(): Condition?

  /**
   * An intermediate table that needs to be joined with this one in order to connect this table to
   * the main table. For example, if table `foo` has a foreign key column `accession_id` and table
   * `bar` has a foreign key `foo_id`, a query of accession data that wants to get a column from
   * `bar` would need to also join with `foo`. In that case, this method would return the
   * [SearchTable] for `foo`.
   *
   * This should be null (the default) for children that can be directly joined with the main table.
   */
  open val parent: SearchTable?
    get() = null

  /**
   * If the user's permission to see rows in this table can't be determined directly from the
   * contents of the table itself, the other table that the query needs to left join with in order
   * to check permissions.
   *
   * Null if the current table has the required information to determine whether the user can see a
   * given row. In that case, [conditionForPermissions] must be non-null.
   */
  abstract val inheritsPermissionsFrom: SearchTable?

  /**
   * Returns a condition to add to the `WHERE` clause of a multiset subquery to correlate it with
   * the current row from the parent table.
   */
  abstract fun conditionForMultiset(): Condition?

  /**
   * Returns the default fields to sort on. These are always included when querying the table; if
   * there are user-supplied sort criteria, these come at the end. This allows us to return stable
   * query results if the user-requested sort fields have duplicate values.
   */
  open val defaultOrderFields: List<OrderField<*>>
    get() =
        fromTable.primaryKey?.fields
            ?: throw IllegalStateException("BUG! No primary key fields found for $fromTable")

  fun bigDecimalField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, BigDecimal?>,
  ) = BigDecimalField(fieldName, displayName, databaseField, this)

  fun dateField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, LocalDate?>,
      nullable: Boolean = false
  ) = DateField(fieldName, displayName, databaseField, this, nullable)

  inline fun <E : Enum<E>, reified T : EnumFromReferenceTable<E>> enumField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, T?>,
      nullable: Boolean = true
  ) = EnumField(fieldName, displayName, databaseField, this, T::class.java, nullable)

  fun gramsField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, BigDecimal?>
  ) = GramsField(fieldName, displayName, databaseField, this)

  fun <T : Any> idWrapperField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, T?>,
      fromLong: (Long) -> T
  ) = IdWrapperField(fieldName, displayName, databaseField, this, fromLong)

  fun integerField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Int?>,
      nullable: Boolean = true
  ) = IntegerField(fieldName, displayName, databaseField, this, nullable)

  fun textField(
      fieldName: String,
      displayName: String,
      databaseField: Field<String?>,
      nullable: Boolean = true
  ) = TextField(fieldName, displayName, databaseField, this, nullable, fuzzySearchOperators)

  fun timestampField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Instant?>,
      nullable: Boolean = true
  ) = TimestampField(fieldName, displayName, databaseField, this, nullable)

  fun upperCaseTextField(
      fieldName: String,
      displayName: String,
      databaseField: Field<String?>,
      nullable: Boolean = true
  ) =
      UpperCaseTextField(
          fieldName, displayName, databaseField, this, nullable, fuzzySearchOperators)
}
