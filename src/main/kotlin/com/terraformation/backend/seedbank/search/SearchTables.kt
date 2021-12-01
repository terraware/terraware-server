package com.terraformation.backend.seedbank.search

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.CollectorId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FamilyId
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.tables.records.AccessionsRecord
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.search.SearchTable
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

/** Definitions of all the available search tables. */
@ManagedBean
class SearchTables(val fuzzySearchOperators: FuzzySearchOperators) {
  val accessions =
      object : SearchTable(fuzzySearchOperators) {
        override val primaryKey: TableField<out Record, out Any?>
          get() = ACCESSIONS.ID

        override val defaultOrderFields: List<OrderField<*>> =
            listOf(ACCESSIONS.NUMBER, ACCESSIONS.ID)

        override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
          // No-op; initial query always selects from accession
          return query
        }

        override fun conditionForPermissions(): Condition {
          return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
        }

        // Table has facility ID
        override val inheritsPermissionsFrom: SearchTable?
          get() = null
      }

  val accessionGerminationTestTypes =
      object :
          AccessionChildTable(
              ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID,
              ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID) {}

  val bags = object : AccessionChildTable(BAGS.ID, BAGS.ACCESSION_ID) {}

  val facilities =
      object : AccessionParentTable<FacilityId>(FACILITIES.ID, ACCESSIONS.FACILITY_ID) {
        override fun conditionForPermissions(): Condition {
          return FACILITIES.ID.`in`(currentUser().facilityRoles.keys)
        }

        override val inheritsPermissionsFrom: SearchTable?
          get() = null
      }

  val geolocations = object : AccessionChildTable(GEOLOCATIONS.ID, GEOLOCATIONS.ACCESSION_ID) {}

  val germinations =
      object : SearchTable(fuzzySearchOperators) {
        override val primaryKey: TableField<out Record, out Any?>
          get() = GERMINATIONS.ID

        override val parent
          get() = germinationTests

        override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
          return parent
              .leftJoinWithMain(query)
              .leftJoin(GERMINATIONS)
              .on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
        }

        override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
          return query.join(GERMINATION_TESTS).on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
        }

        override fun conditionForPermissions(): Condition? {
          return null
        }

        override val inheritsPermissionsFrom: SearchTable
          get() = germinationTests
      }

  val germinationTests =
      object : AccessionChildTable(GERMINATION_TESTS.ID, GERMINATION_TESTS.ACCESSION_ID) {}

  val primaryCollectors =
      object : AccessionParentTable<CollectorId>(COLLECTORS.ID, ACCESSIONS.PRIMARY_COLLECTOR_ID) {
        override fun conditionForPermissions(): Condition {
          return COLLECTORS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
        }

        override val inheritsPermissionsFrom: SearchTable?
          get() = null
      }

  val species =
      object : AccessionParentTable<SpeciesId>(SPECIES.ID, ACCESSIONS.SPECIES_ID) {
        override fun conditionForPermissions(): Condition? {
          return null
        }

        // TODO: Make this check species ownership once we have per-org species.
        override val inheritsPermissionsFrom: SearchTable?
          get() = null
      }

  val families =
      object : AccessionParentTable<FamilyId>(FAMILIES.ID, ACCESSIONS.FAMILY_ID) {
        override fun conditionForPermissions(): Condition? {
          return null
        }

        override val inheritsPermissionsFrom: SearchTable?
          get() = null
      }

  val storageLocations =
      object :
          AccessionParentTable<StorageLocationId>(
              STORAGE_LOCATIONS.ID, ACCESSIONS.STORAGE_LOCATION_ID) {
        override fun conditionForPermissions(): Condition {
          return STORAGE_LOCATIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
        }

        override val inheritsPermissionsFrom: SearchTable?
          get() = null
      }

  val withdrawals = object : AccessionChildTable(WITHDRAWALS.ID, WITHDRAWALS.ACCESSION_ID) {}

  /**
   * Base class for tables that act as parents of the accession table. That is, tables whose primary
   * keys are referenced by foreign key columns in the accession table and thus have a one-to-many
   * relationship with accessions. Typically these are tables that hold the list of choices for
   * enumerated accession fields.
   *
   * @param primaryKey The field on the parent table that is referenced by the foreign key on the
   * accession table.
   * @param accessionForeignKeyField The foreign key field on the accession table that references
   * the parent table.
   */
  abstract inner class AccessionParentTable<T>(
      override val primaryKey: TableField<out Record, T?>,
      private val accessionForeignKeyField: TableField<AccessionsRecord, T?>
  ) : SearchTable(fuzzySearchOperators) {
    override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
      return query.leftJoin(primaryKey.table!!).on(primaryKey.eq(accessionForeignKeyField))
    }
  }

  /**
   * Base class for tables that act as children of the accession table. That is, tables that have an
   * foreign key column pointing to the accession table, and hence have a many-to-one relationship
   * with accessions.
   *
   * @param accessionIdField The field that contains the accession ID.
   */
  abstract inner class AccessionChildTable(
      override val primaryKey: TableField<out Record, out Any?>,
      private val accessionIdField: TableField<*, AccessionId?>
  ) : SearchTable(fuzzySearchOperators) {
    override fun <T : Record> leftJoinWithMain(query: SelectJoinStep<T>): SelectJoinStep<T> {
      return query.leftJoin(accessionIdField.table!!).on(accessionIdField.eq(ACCESSIONS.ID))
    }

    override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
      return query.join(ACCESSIONS).on(accessionIdField.eq(ACCESSIONS.ID))
    }

    override fun conditionForPermissions(): Condition? {
      return null
    }

    override val inheritsPermissionsFrom: SearchTable?
      get() = accessions
  }
}
