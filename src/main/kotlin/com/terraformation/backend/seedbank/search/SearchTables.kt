package com.terraformation.backend.seedbank.search

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.CollectorId
import com.terraformation.backend.db.SpeciesFamilyId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.tables.records.AccessionsRecord
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.SPECIES_FAMILIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import org.jooq.Condition
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
  fun leftJoinWithAccession(query: SelectJoinStep<out Record>): SelectJoinStep<out Record>

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
  fun joinForPermissions(query: SelectJoinStep<out Record>): SelectJoinStep<out Record> = query

  /**
   * Returns a condition that restricts this table's values to ones the user has permission to see.
   * If the table's values are visible to all users, returns null.
   *
   * This method can safely assume that [joinForPermissions] was called, so any tables added there
   * are available for use in the condition.
   */
  fun conditionForPermissions(): Condition?

  /**
   * Returns a set of intermediate tables that need to be joined in order to connect this table to
   * the accession table. This supports multi-step chains of foreign keys. For example, if table
   * `foo` has a foreign key column `accession_id` and table `bar` has a foreign key `foo_id`, a
   * query that wants to get a column from `bar` would need to also join with `foo`. In that case,
   * this method would return the [SearchTable] for `foo`.
   *
   * The default implementation returns an empty set, suitable for tables that can be directly
   * joined with the accession table.
   */
  fun dependsOn(): Set<SearchTable> = emptySet()
}

/**
 * Base class for tables that act as children of the accession table. That is, tables that have an
 * foreign key column pointing to the accession table, and hence have a many-to-one relationship
 * with accessions.
 *
 * @param idField The field that contains the accession ID.
 */
abstract class AccessionChildTable(private val idField: TableField<*, AccessionId?>) : SearchTable {
  override val fromTable
    get() = idField.table!!

  override fun leftJoinWithAccession(
      query: SelectJoinStep<out Record>
  ): SelectJoinStep<out Record> {
    return query.leftJoin(idField.table!!).on(idField.eq(ACCESSIONS.ID))
  }

  override fun joinForPermissions(query: SelectJoinStep<out Record>): SelectJoinStep<out Record> {
    return query.join(ACCESSIONS).on(idField.eq(ACCESSIONS.ID))
  }

  override fun conditionForPermissions(): Condition? {
    return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
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

  override fun leftJoinWithAccession(
      query: SelectJoinStep<out Record>
  ): SelectJoinStep<out Record> {
    return query
        .leftJoin(parentTableIdField.table!!)
        .on(parentTableIdField.eq(accessionForeignKeyField))
  }
}

/**
 * Definitions of all the available search tables. This class is never instantiated and acts as a
 * namespace.
 */
class SearchTables {
  object Accession : SearchTable {
    override val fromTable
      get() = ACCESSIONS

    override fun leftJoinWithAccession(
        query: SelectJoinStep<out Record>
    ): SelectJoinStep<out Record> {
      // No-op; initial query always selects from accession
      return query
    }

    override fun conditionForPermissions(): Condition {
      return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
    }
  }

  object AccessionGerminationTestType :
      AccessionChildTable(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID)

  object Bag : AccessionChildTable(BAGS.ACCESSION_ID)

  object Geolocation : AccessionChildTable(GEOLOCATIONS.ACCESSION_ID)

  object Germination : SearchTable {
    override val fromTable
      get() = GERMINATIONS

    override fun dependsOn(): Set<SearchTable> {
      return setOf(GerminationTest)
    }

    override fun leftJoinWithAccession(
        query: SelectJoinStep<out Record>
    ): SelectJoinStep<out Record> {
      // We'll already be joined with germination_test
      return query.leftJoin(GERMINATIONS).on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
    }

    override fun joinForPermissions(query: SelectJoinStep<out Record>): SelectJoinStep<out Record> {
      return query
          .join(GERMINATION_TESTS)
          .on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
          .join(ACCESSIONS)
          .on(GERMINATION_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID))
    }

    override fun conditionForPermissions(): Condition {
      return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
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

  object SpeciesFamily :
      AccessionParentTable<SpeciesFamilyId>(SPECIES_FAMILIES.ID, ACCESSIONS.SPECIES_FAMILY_ID) {
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
