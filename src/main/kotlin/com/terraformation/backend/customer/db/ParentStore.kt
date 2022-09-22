package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.FacilityConnectionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.AUTOMATIONS
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.UPLOADS
import com.terraformation.backend.db.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import javax.annotation.ManagedBean
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
@ManagedBean
class ParentStore(private val dslContext: DSLContext) {
  fun getAccessionId(viabilityTestId: ViabilityTestId): AccessionId? =
      fetchFieldById(viabilityTestId, VIABILITY_TESTS.ID, VIABILITY_TESTS.ACCESSION_ID)

  fun getAccessionId(withdrawalId: WithdrawalId): AccessionId? =
      fetchFieldById(withdrawalId, WITHDRAWALS.ID, WITHDRAWALS.ACCESSION_ID)

  fun getFacilityId(accessionId: AccessionId): FacilityId? =
      fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.FACILITY_ID)

  fun getFacilityId(automationId: AutomationId): FacilityId? =
      fetchFieldById(automationId, AUTOMATIONS.ID, AUTOMATIONS.FACILITY_ID)

  fun getFacilityId(deviceManagerId: DeviceManagerId): FacilityId? =
      fetchFieldById(deviceManagerId, DEVICE_MANAGERS.ID, DEVICE_MANAGERS.FACILITY_ID)

  fun getFacilityId(deviceId: DeviceId): FacilityId? =
      fetchFieldById(deviceId, DEVICES.ID, DEVICES.FACILITY_ID)

  fun getFacilityId(storageLocationId: StorageLocationId): FacilityId? =
      fetchFieldById(storageLocationId, STORAGE_LOCATIONS.ID, STORAGE_LOCATIONS.FACILITY_ID)

  fun getOrganizationId(deviceManagerId: DeviceManagerId): OrganizationId? =
      fetchFieldById(
          deviceManagerId, DEVICE_MANAGERS.ID, DEVICE_MANAGERS.facilities().ORGANIZATION_ID)

  fun getOrganizationId(facilityId: FacilityId): OrganizationId? =
      fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.ORGANIZATION_ID)

  fun getOrganizationId(speciesId: SpeciesId): OrganizationId? =
      fetchFieldById(speciesId, SPECIES.ID, SPECIES.ORGANIZATION_ID)

  fun getUserId(notificationId: NotificationId): UserId? =
      fetchFieldById(notificationId, NOTIFICATIONS.ID, NOTIFICATIONS.USER_ID)

  fun getOrganizationId(accessionId: AccessionId): OrganizationId? {
    return fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.facilities().ORGANIZATION_ID)
  }

  fun getFacilityConnectionState(deviceId: DeviceId): FacilityConnectionState {
    return fetchFieldById(deviceId, DEVICES.ID, DEVICES.facilities().CONNECTION_STATE_ID)
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
