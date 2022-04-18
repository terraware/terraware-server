package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNameId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.AUTOMATIONS
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.SPECIES_NAMES
import com.terraformation.backend.db.tables.references.SPECIES_OPTIONS
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField

/**
 * Lookup methods to get the IDs of the parents of various objects.
 *
 * This is mostly called by [IndividualUser] to evaluate permissions on child objects in cases where
 * the children inherit permissions from parents. Putting all these lookups in one place reduces the
 * number of dependencies in [IndividualUser], and also gives us a clean place to introduce caching
 * if parent ID lookups in permission checks become a performance bottleneck.
 */
@ManagedBean
class ParentStore(private val dslContext: DSLContext) {
  fun getFacilityId(accessionId: AccessionId): FacilityId? =
      fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.FACILITY_ID)

  fun getFacilityId(automationId: AutomationId): FacilityId? =
      fetchFieldById(automationId, AUTOMATIONS.ID, AUTOMATIONS.FACILITY_ID)

  fun getFacilityId(deviceId: DeviceId): FacilityId? =
      fetchFieldById(deviceId, DEVICES.ID, DEVICES.FACILITY_ID)

  fun getFacilityId(storageLocationId: StorageLocationId): FacilityId? =
      fetchFieldById(storageLocationId, STORAGE_LOCATIONS.ID, STORAGE_LOCATIONS.FACILITY_ID)

  fun getProjectId(facilityId: FacilityId): ProjectId? =
      fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.sites().PROJECT_ID)

  fun getProjectId(siteId: SiteId): ProjectId? = fetchFieldById(siteId, SITES.ID, SITES.PROJECT_ID)

  fun getOrganizationId(projectId: ProjectId): OrganizationId? =
      fetchFieldById(projectId, PROJECTS.ID, PROJECTS.ORGANIZATION_ID)

  fun getOrganizationId(facilityId: FacilityId): OrganizationId? =
      fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.sites().projects().ORGANIZATION_ID)

  fun getOrganizationIds(speciesId: SpeciesId): List<OrganizationId> {
    return dslContext
        .select(SPECIES_OPTIONS.ORGANIZATION_ID)
        .from(SPECIES_OPTIONS)
        .where(SPECIES_OPTIONS.SPECIES_ID.eq(speciesId))
        .fetch(SPECIES_OPTIONS.ORGANIZATION_ID)
        .filterNotNull()
  }

  fun getOrganizationId(speciesNameId: SpeciesNameId): OrganizationId? =
      fetchFieldById(speciesNameId, SPECIES_NAMES.ID, SPECIES_NAMES.ORGANIZATION_ID)

  /**
   * Looks up a database row by an ID and returns the value of one of the columns, or null if no row
   * had the given ID.
   */
  private fun <C, P, R : Record> fetchFieldById(
      id: C,
      idField: TableField<R, C>,
      fieldToFetch: Field<P>
  ): P? {
    return dslContext
        .select(fieldToFetch)
        .from(idField.table)
        .where(idField.eq(id))
        .fetchOne(fieldToFetch)
  }
}
