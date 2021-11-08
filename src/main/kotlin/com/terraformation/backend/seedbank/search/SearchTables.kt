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
import com.terraformation.backend.search.SearchTable
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

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

  override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
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

  override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
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

    override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
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

    override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
      return parent
          .leftJoinWithMain(query)
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
