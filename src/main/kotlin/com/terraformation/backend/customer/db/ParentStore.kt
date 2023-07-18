package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import jakarta.inject.Named
import java.time.ZoneId
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Lookup methods to get the IDs of the parents of various objects.
 *
 * This is mostly called by [IndividualUser] to evaluate permissions on child objects in cases where
 * the children inherit permissions from parents. Putting all these lookups in one place reduces the
 * number of dependencies in [IndividualUser], and also gives us a clean place to introduce caching
 * if parent ID lookups in permission checks become a performance bottleneck.
 */
@Named
class ParentStore(private val dslContext: DSLContext) {
  fun getFacilityId(accessionId: AccessionId): FacilityId? =
      fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.FACILITY_ID)

  fun getFacilityId(automationId: AutomationId): FacilityId? =
      fetchFieldById(automationId, AUTOMATIONS.ID, AUTOMATIONS.FACILITY_ID)

  fun getFacilityId(batchId: BatchId): FacilityId? =
      fetchFieldById(batchId, BATCHES.ID, BATCHES.FACILITY_ID)

  fun getFacilityId(deviceManagerId: DeviceManagerId): FacilityId? =
      fetchFieldById(deviceManagerId, DEVICE_MANAGERS.ID, DEVICE_MANAGERS.FACILITY_ID)

  fun getFacilityId(deviceId: DeviceId): FacilityId? =
      fetchFieldById(deviceId, DEVICES.ID, DEVICES.FACILITY_ID)

  fun getFacilityId(storageLocationId: StorageLocationId): FacilityId? =
      fetchFieldById(storageLocationId, STORAGE_LOCATIONS.ID, STORAGE_LOCATIONS.FACILITY_ID)

  fun getFacilityId(viabilityTestId: ViabilityTestId): FacilityId? =
      fetchFieldById(viabilityTestId, VIABILITY_TESTS.ID, VIABILITY_TESTS.accessions.FACILITY_ID)

  fun getFacilityId(withdrawalId: WithdrawalId): FacilityId? =
      fetchFieldById(withdrawalId, WITHDRAWALS.ID, WITHDRAWALS.FACILITY_ID)

  fun getOrganizationId(batchId: BatchId): OrganizationId? =
      fetchFieldById(batchId, BATCHES.ID, BATCHES.ORGANIZATION_ID)

  fun getOrganizationId(deliveryId: DeliveryId): OrganizationId? =
      fetchFieldById(deliveryId, DELIVERIES.ID, DELIVERIES.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(deviceManagerId: DeviceManagerId): OrganizationId? =
      fetchFieldById(
          deviceManagerId, DEVICE_MANAGERS.ID, DEVICE_MANAGERS.facilities.ORGANIZATION_ID)

  fun getOrganizationId(facilityId: FacilityId): OrganizationId? =
      fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.ORGANIZATION_ID)

  fun getOrganizationId(observationId: ObservationId): OrganizationId? =
      fetchFieldById(observationId, OBSERVATIONS.ID, OBSERVATIONS.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(plantingId: PlantingId): OrganizationId? =
      fetchFieldById(plantingId, PLANTINGS.ID, PLANTINGS.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(plantingSiteId: PlantingSiteId): OrganizationId? =
      fetchFieldById(plantingSiteId, PLANTING_SITES.ID, PLANTING_SITES.ORGANIZATION_ID)

  fun getOrganizationId(plantingSubzoneId: PlantingSubzoneId): OrganizationId? =
      fetchFieldById(
          plantingSubzoneId, PLANTING_SUBZONES.ID, PLANTING_SUBZONES.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(plantingZoneId: PlantingZoneId): OrganizationId? =
      fetchFieldById(
          plantingZoneId, PLANTING_ZONES.ID, PLANTING_ZONES.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(projectId: ProjectId): OrganizationId? =
      fetchFieldById(projectId, PROJECTS.ID, PROJECTS.ORGANIZATION_ID)

  fun getOrganizationId(reportId: ReportId): OrganizationId? =
      fetchFieldById(reportId, REPORTS.ID, REPORTS.ORGANIZATION_ID)

  fun getOrganizationId(speciesId: SpeciesId): OrganizationId? =
      fetchFieldById(speciesId, SPECIES.ID, SPECIES.ORGANIZATION_ID)

  fun getUserId(notificationId: NotificationId): UserId? =
      fetchFieldById(notificationId, NOTIFICATIONS.ID, NOTIFICATIONS.USER_ID)

  fun getOrganizationId(accessionId: AccessionId): OrganizationId? {
    return fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.facilities.ORGANIZATION_ID)
  }

  fun getFacilityConnectionState(deviceId: DeviceId): FacilityConnectionState {
    return fetchFieldById(deviceId, DEVICES.ID, DEVICES.facilities.CONNECTION_STATE_ID)
        ?: throw DeviceNotFoundException(deviceId)
  }

  fun getFacilityName(accessionId: AccessionId): String {
    val facilityId = getFacilityId(accessionId) ?: throw AccessionNotFoundException(accessionId)
    return fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.NAME)
        ?: throw FacilityNotFoundException(facilityId)
  }

  fun getFacilityType(facilityId: FacilityId): FacilityType =
      fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.TYPE_ID)
          ?: throw FacilityNotFoundException(facilityId)

  fun getUserId(uploadId: UploadId): UserId? =
      fetchFieldById(uploadId, UPLOADS.ID, UPLOADS.CREATED_BY)

  fun getDeviceManagerId(userId: UserId): DeviceManagerId? =
      fetchFieldById(userId, DEVICE_MANAGERS.USER_ID, DEVICE_MANAGERS.ID)

  fun getEffectiveTimeZone(accessionId: AccessionId): ZoneId =
      fetchFieldById(
          accessionId,
          ACCESSIONS.ID,
          DSL.coalesce(
              ACCESSIONS.facilities.TIME_ZONE, ACCESSIONS.facilities.organizations.TIME_ZONE))
          ?: ZoneOffset.UTC

  fun exists(deviceManagerId: DeviceManagerId): Boolean =
      fetchFieldById(deviceManagerId, DEVICE_MANAGERS.ID, DSL.one()) != null

  fun exists(organizationId: OrganizationId, userId: UserId): Boolean =
      dslContext
          .selectOne()
          .from(ORGANIZATION_USERS)
          .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
          .and(ORGANIZATION_USERS.USER_ID.eq(userId))
          .fetch()
          .isNotEmpty

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
