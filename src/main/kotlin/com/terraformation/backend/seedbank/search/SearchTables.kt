package com.terraformation.backend.seedbank.search

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.CollectorId
import com.terraformation.backend.db.FamilyId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.tables.records.AccessionsRecord
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import org.jooq.Condition
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
interface SearchTable {
  /** The jOOQ Table object for the table in question. */
  val fromTable: Table<out Record>

  /**
   * Adds a LEFT JOIN clause to a query to connect this table to the accession table. The
   * implementation can assume that the accession table is already present in the SELECT statement.
   */
  fun <T : Record> leftJoinWithAccession(query: SelectJoinStep<T>): SelectJoinStep<T>

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

/**
 * Base class for tables that act as children of the accession table. That is, tables that have an
 * foreign key column pointing to the accession table, and hence have a many-to-one relationship
 * with accessions.
 *
 * @param accessionIdField The field that contains the accession ID.
 */
abstract class AccessionChildTable(private val accessionIdField: TableField<*, AccessionId?>) :
    SearchTable {
  override val fromTable
    get() = accessionIdField.table!!

  override fun <T : Record> leftJoinWithAccession(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.leftJoin(accessionIdField.table!!).on(accessionIdField.eq(ACCESSIONS.ID))
  }

  override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(accessionIdField.eq(ACCESSIONS.ID))
  }

  override fun conditionForPermissions(): Condition? {
    return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForMultiset(): Condition? {
    return accessionIdField.eq(ACCESSIONS.ID)
  }
}

/**
 * Base class for tables that act as parents of the accession table. That is, tables whose primary
 * keys are referenced by foreign key columns in the accession table and thus have a one-to-many
 * relationship with accessions. Typically these are tables that hold the list of choices for
 * enumerated accession fields.
 *
 * @param parentTableIdField The field on the parent table that is referenced by the foreign key on
 * the accession table.
 * @param accessionForeignKeyField The foreign key field on the accession table that references the
 * parent table.
 */
abstract class AccessionParentTable<T>(
    private val parentTableIdField: TableField<*, T?>,
    private val accessionForeignKeyField: TableField<AccessionsRecord, T?>
) : SearchTable {
  override val fromTable
    get() = parentTableIdField.table!!

  override fun <T : Record> leftJoinWithAccession(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .leftJoin(parentTableIdField.table!!)
        .on(parentTableIdField.eq(accessionForeignKeyField))
  }

  override fun conditionForMultiset(): Condition? = null
}

/**
 * Definitions of all the available search tables. This class is never instantiated and acts as a
 * namespace.
 */
class SearchTables {
  object Accession : SearchTable {
    override val fromTable
      get() = ACCESSIONS

    override val defaultOrderFields: List<OrderField<*>> = listOf(ACCESSIONS.NUMBER, ACCESSIONS.ID)

    override fun <T : Record> leftJoinWithAccession(query: SelectJoinStep<T>): SelectJoinStep<T> {
      // No-op; initial query always selects from accession
      return query
    }

    override fun conditionForPermissions(): Condition {
      return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
    }

    override fun conditionForMultiset(): Condition? {
      // No-op; this is always the topmost table
      return null
    }
  }

  object AccessionGerminationTestType :
      AccessionChildTable(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID)

  object Bag : AccessionChildTable(BAGS.ACCESSION_ID)

  object Geolocation : AccessionChildTable(GEOLOCATIONS.ACCESSION_ID)

  object Germination : SearchTable {
    override val fromTable
      get() = GERMINATIONS

    override val parent
      get() = GerminationTest

    override fun <T : Record> leftJoinWithAccession(query: SelectJoinStep<T>): SelectJoinStep<T> {
      return parent
          .leftJoinWithAccession(query)
          .leftJoin(GERMINATIONS)
          .on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
    }

    override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
      return query
          .join(GERMINATION_TESTS)
          .on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
          .join(ACCESSIONS)
          .on(GERMINATION_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID))
    }

    override fun conditionForPermissions(): Condition {
      return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
    }

    override fun conditionForMultiset(): Condition {
      return GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID)
    }
  }

  object GerminationTest : AccessionChildTable(GERMINATION_TESTS.ACCESSION_ID)

  object PrimaryCollector :
      AccessionParentTable<CollectorId>(COLLECTORS.ID, ACCESSIONS.PRIMARY_COLLECTOR_ID) {
    override fun conditionForPermissions(): Condition {
      return COLLECTORS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
    }
  }

  object Species : AccessionParentTable<SpeciesId>(SPECIES.ID, ACCESSIONS.SPECIES_ID) {
    override fun conditionForPermissions(): Condition? {
      return null
    }
  }

  object Family : AccessionParentTable<FamilyId>(FAMILIES.ID, ACCESSIONS.FAMILY_ID) {
    override fun conditionForPermissions(): Condition? {
      return null
    }
  }

  object StorageLocation :
      AccessionParentTable<StorageLocationId>(
          STORAGE_LOCATIONS.ID, ACCESSIONS.STORAGE_LOCATION_ID) {
    override fun conditionForPermissions(): Condition {
      return STORAGE_LOCATIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
    }
  }

  object Withdrawal : AccessionChildTable(WITHDRAWALS.ACCESSION_ID)
}
