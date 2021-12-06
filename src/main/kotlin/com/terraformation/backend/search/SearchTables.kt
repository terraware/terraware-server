package com.terraformation.backend.search

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
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
      object : PerFacilityTable(ACCESSIONS.ID, ACCESSIONS.FACILITY_ID) {
        override val defaultOrderFields: List<OrderField<*>>
          get() = listOf(ACCESSIONS.NUMBER, ACCESSIONS.ID)
      }

  val accessionGerminationTestTypes =
      object :
          AccessionChildTable(
              ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID,
              ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID) {}

  val bags = object : AccessionChildTable(BAGS.ID, BAGS.ACCESSION_ID) {}

  val collectors = object : PerFacilityTable(COLLECTORS.ID, COLLECTORS.FACILITY_ID) {}

  val facilities = object : PerFacilityTable(FACILITIES.ID, FACILITIES.ID) {}

  val geolocations = object : AccessionChildTable(GEOLOCATIONS.ID, GEOLOCATIONS.ACCESSION_ID) {}

  val germinationTests =
      object : AccessionChildTable(GERMINATION_TESTS.ID, GERMINATION_TESTS.ACCESSION_ID) {}

  val germinations =
      object :
          SearchTable(
              fuzzySearchOperators, GERMINATIONS.ID, inheritsPermissionsFrom = germinationTests) {
        override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
          return query.join(GERMINATION_TESTS).on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
        }
      }

  val organizations = object : PerOrganizationTable(ORGANIZATIONS.ID, ORGANIZATIONS.ID) {}

  val projects = object : PerProjectTable(PROJECTS.ID, PROJECTS.ID) {}

  val sites = object : PerProjectTable(SITES.ID, SITES.PROJECT_ID) {}

  val species =
      object : SearchTable(fuzzySearchOperators, SPECIES.ID) {
        // TODO: Add permission condition once we have per-org species.
      }

  val families =
      object : SearchTable(fuzzySearchOperators, FAMILIES.ID) {
        // TODO: Add permission condition once we have per-org species (if we're keeping this table)
      }

  val storageLocations =
      object : PerFacilityTable(STORAGE_LOCATIONS.ID, STORAGE_LOCATIONS.FACILITY_ID) {}

  val withdrawals = object : AccessionChildTable(WITHDRAWALS.ID, WITHDRAWALS.ACCESSION_ID) {}

  val layers =
      object : SearchTable(fuzzySearchOperators, LAYERS.ID, inheritsPermissionsFrom = sites) {
        override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
          return query.join(SITES).on(LAYERS.SITE_ID.eq(SITES.ID))
        }
      }

  val features =
      object : SearchTable(fuzzySearchOperators, FEATURES.ID, inheritsPermissionsFrom = layers) {
        override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
          return query.join(LAYERS).on(FEATURES.LAYER_ID.eq(LAYERS.ID))
        }
      }

  val plants =
      object :
          SearchTable(fuzzySearchOperators, PLANTS.FEATURE_ID, inheritsPermissionsFrom = features) {
        override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
          return query.join(FEATURES).on(PLANTS.FEATURE_ID.eq(FEATURES.ID))
        }
      }

  /** Base class for tables with per-organization permissions. */
  abstract inner class PerOrganizationTable(
      primaryKey: TableField<out Record, out Any?>,
      private val organizationIdField: TableField<*, OrganizationId?>
  ) : SearchTable(fuzzySearchOperators, primaryKey) {
    override fun conditionForPermissions(): Condition? {
      return organizationIdField.`in`(currentUser().organizationRoles.keys)
    }
  }

  /** Base class for tables with per-project permissions. */
  abstract inner class PerProjectTable(
      primaryKey: TableField<out Record, out Any?>,
      private val projectIdField: TableField<*, ProjectId?>
  ) : SearchTable(fuzzySearchOperators, primaryKey) {
    override fun conditionForPermissions(): Condition? {
      return projectIdField.`in`(currentUser().projectRoles.keys)
    }
  }

  /** Base class for tables with per-facility permissions. */
  abstract inner class PerFacilityTable(
      primaryKey: TableField<out Record, out Any?>,
      private val facilityIdField: TableField<*, FacilityId?>
  ) : SearchTable(fuzzySearchOperators, primaryKey) {
    override fun conditionForPermissions(): Condition? {
      return facilityIdField.`in`(currentUser().facilityRoles.keys)
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
      primaryKey: TableField<out Record, out Any?>,
      private val accessionIdField: TableField<*, AccessionId?>
  ) : SearchTable(fuzzySearchOperators, primaryKey, accessions) {
    override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
      return query.join(ACCESSIONS).on(accessionIdField.eq(ACCESSIONS.ID))
    }
  }
}
