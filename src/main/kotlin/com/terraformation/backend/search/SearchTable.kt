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
 * Defines a table whose columns can be declared as [SearchField]s. The methods here are used in
 * [SearchService] when it dynamically constructs SQL queries based on a search request from a
 * client.
 */
abstract class SearchTable(
    private val fuzzySearchOperators: FuzzySearchOperators,

    /** The primary key column for the table in question. */
    val primaryKey: TableField<out Record, out Any?>,

    /**
     * If the user's permission to see rows in this table can't be determined directly from the
     * contents of the table itself, the other table that the query needs to left join with in order
     * to check permissions.
     *
     * Null if the current table has the required information to determine whether the user can see
     * a given row. In that case, [conditionForPermissions] must be non-null.
     */
    val inheritsPermissionsFrom: SearchTable? = null,
) {
  /** The jOOQ Table object for the table in question. */
  open val fromTable: Table<out Record>
    get() = primaryKey.table ?: throw IllegalStateException("$primaryKey has no table")

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
  open fun conditionForPermissions(): Condition? = null

  /**
   * Returns the default fields to sort on. These are included when doing non-distinct queries; if
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
