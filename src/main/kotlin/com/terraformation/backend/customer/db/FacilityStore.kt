package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityAlreadyConnectedException
import com.terraformation.backend.db.FacilityConnectionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.security.access.AccessDeniedException

/** Permission-aware accessors for facility information. */
@ManagedBean
class FacilityStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val facilitiesDao: FacilitiesDao,
    private val storageLocationsDao: StorageLocationsDao,
) {
  /** Maximum device manager idle time, in minutes, to assign to new facilities by default. */
  private val defaultMaxIdleMinutes = 30

  private val log = perClassLogger()

  fun fetchById(facilityId: FacilityId): FacilityModel? {
    return if (!currentUser().canReadFacility(facilityId)) {
      null
    } else {
      facilitiesDao.fetchOneById(facilityId)?.toModel()
    }
  }

  /** Returns a list of all the facilities the current user can access. */
  fun fetchAll(): List<FacilityModel> {
    val availableIds = currentUser().facilityRoles.keys.toTypedArray()
    return if (availableIds.isEmpty()) {
      emptyList()
    } else {
      facilitiesDao.fetchById(*availableIds).map { it.toModel() }
    }
  }

  /** Returns all the facilities the current user can access at a site. */
  fun fetchBySiteId(siteId: SiteId): List<FacilityModel> {
    return if (currentUser().canListFacilities(siteId)) {
      facilitiesDao.fetchBySiteId(siteId).map { it.toModel() }
    } else {
      emptyList()
    }
  }

  /**
   * Creates a new facility.
   *
   * @throws AccessDeniedException The current user does not have permission to create facilities at
   * the site.
   */
  fun create(
      siteId: SiteId,
      type: FacilityType,
      name: String,
      description: String? = null,
      maxIdleMinutes: Int = defaultMaxIdleMinutes,
      createStorageLocations: Boolean = true,
  ): FacilityModel {
    requirePermissions { createFacility(siteId) }

    val row =
        FacilitiesRow(
            connectionStateId = FacilityConnectionState.NotConnected,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            description = description,
            maxIdleMinutes = maxIdleMinutes,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
            siteId = siteId,
            typeId = type,
        )

    facilitiesDao.insert(row)
    val model = row.toModel()

    if (type == FacilityType.SeedBank && createStorageLocations) {
      (1..3).forEach { num ->
        createStorageLocation(model.id, "Refrigerator $num", StorageCondition.Refrigerator)
        createStorageLocation(model.id, "Freezer $num", StorageCondition.Freezer)
      }
    }

    return model
  }

  /**
   * Updates the settings of an existing facility. Connection state is ignored here; use
   * [updateConnectionState] to modify it.
   */
  fun update(model: FacilityModel) {
    val facilityId = model.id
    requirePermissions { updateFacility(facilityId) }

    if (model.maxIdleMinutes < 1) {
      throw IllegalArgumentException("Maximum idle minutes must be greater than 0")
    }

    val existingRow =
        facilitiesDao.fetchOneById(facilityId) ?: throw FacilityNotFoundException(facilityId)

    facilitiesDao.update(
        existingRow.copy(
            description = model.description,
            maxIdleMinutes = model.maxIdleMinutes,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = model.name,
            typeId = model.type))
  }

  fun fetchStorageLocations(facilityId: FacilityId): List<StorageLocationsRow> {
    requirePermissions { readFacility(facilityId) }

    return storageLocationsDao.fetchByFacilityId(facilityId).filter {
      val storageLocationId = it.id
      storageLocationId != null && currentUser().canReadStorageLocation(storageLocationId)
    }
  }

  fun createStorageLocation(
      facilityId: FacilityId,
      name: String,
      condition: StorageCondition
  ): StorageLocationId {
    requirePermissions { createStorageLocation(facilityId) }

    val row =
        StorageLocationsRow(
            conditionId = condition,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            enabled = true,
            facilityId = facilityId,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
        )

    storageLocationsDao.insert(row)

    return row.id ?: throw IllegalStateException("ID not present after insertion")
  }

  fun updateStorageLocation(
      storageLocationId: StorageLocationId,
      name: String,
      condition: StorageCondition
  ) {
    requirePermissions { updateStorageLocation(storageLocationId) }

    with(STORAGE_LOCATIONS) {
      dslContext
          .update(STORAGE_LOCATIONS)
          .set(CONDITION_ID, condition)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NAME, name)
          .execute()
    }
  }

  /**
   * Transitions a facility's connection state.
   *
   * @throws FacilityAlreadyConnectedException The facility was already in a connected state.
   * @throws IllegalStateException The facility wasn't in the expected state. Only thrown if the
   * expected state is not [FacilityConnectionState.NotConnected].
   */
  fun updateConnectionState(
      facilityId: FacilityId,
      expectedState: FacilityConnectionState,
      newState: FacilityConnectionState
  ) {
    requirePermissions { updateFacility(facilityId) }

    with(FACILITIES) {
      val rowsUpdated =
          dslContext
              .update(FACILITIES)
              .set(CONNECTION_STATE_ID, newState)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .where(ID.eq(facilityId))
              .and(CONNECTION_STATE_ID.eq(expectedState))
              .execute()

      if (rowsUpdated != 1) {
        val currentState =
            dslContext
                .select(FACILITIES.CONNECTION_STATE_ID)
                .from(FACILITIES)
                .where(FACILITIES.ID.eq(facilityId))
                .fetchOne(FACILITIES.CONNECTION_STATE_ID)
                ?: throw FacilityNotFoundException(facilityId)

        if (expectedState == FacilityConnectionState.NotConnected) {
          throw FacilityAlreadyConnectedException(facilityId)
        } else {
          log.error(
              "Facility $facilityId was in connection state $currentState, not $expectedState")
          throw IllegalStateException("Facility was not in expected connection state")
        }
      }
    }
  }

  /**
   * Deletes a storage location. This will only succeed if the storage location is not referenced by
   * any accessions.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException The storage location is in use.
   */
  fun deleteStorageLocation(storageLocationId: StorageLocationId) {
    requirePermissions { deleteStorageLocation(storageLocationId) }

    dslContext
        .deleteFrom(STORAGE_LOCATIONS)
        .where(STORAGE_LOCATIONS.ID.eq(storageLocationId))
        .execute()
  }

  /**
   * Updates the last timeseries times of facilities to indicate that new values have been received
   * from devices.
   */
  fun updateLastTimeseriesTimes(deviceIds: Collection<DeviceId>) {
    if (deviceIds.isNotEmpty()) {
      val uniqueDeviceIds = deviceIds.toSet()

      requirePermissions { uniqueDeviceIds.forEach { updateTimeseries(it) } }

      // We want to add the maximum idle minutes to the current time to get the time when the
      // facility should be counted as idle. The addition operator on a timestamp field takes a
      // decimal number of days on the right-hand side. So we want an expression that converts
      // the value of the max_idle_minutes column to days.
      val maxIdleDaysField = FACILITIES.MAX_IDLE_MINUTES.div(24.0 * 60)
      val now = clock.instant()
      val idleAfterTimeField = DSL.instant(now).add(maxIdleDaysField)

      val rowsUpdated =
          dslContext
              .update(FACILITIES)
              .set(FACILITIES.LAST_TIMESERIES_TIME, now)
              .setNull(FACILITIES.IDLE_SINCE_TIME)
              .set(FACILITIES.IDLE_AFTER_TIME, idleAfterTimeField)
              .where(
                  FACILITIES.ID.`in`(
                      DSL.select(DEVICES.FACILITY_ID)
                          .from(DEVICES)
                          .where(DEVICES.ID.`in`(uniqueDeviceIds))))
              .execute()

      if (rowsUpdated < 1) {
        log.error("No facility timestamps updated for device IDs $uniqueDeviceIds")
      }
    }
  }

  /**
   * Runs an operation on facilities whose device managers have recently stopped submitting new
   * timeseries values. "Recently" means the number of minutes specified in
   * `facilities.max_idle_minutes`.
   *
   * A given facility will only be passed to [func] once per idle period. That is, if a facility
   * goes idle and its ID is passed to [func], a subsequent call to this method won't pass the same
   * ID to [func] again unless it has received new timeseries values in the meantime.
   *
   * However, if [func] throws an exception, the above doesn't apply, and a subsequent call to this
   * method will include the same ID again. (This is why the method takes a function argument
   * instead of just returning a set of IDs; we want to ensure that if, e.g., the server host dies
   * in the middle of handling newly-idle facilities, they'll be taken care of later.)
   *
   * @param func Function to call with the IDs of newly-idle facilities. This is called in a
   * database transaction.
   */
  fun withIdleFacilities(func: (Set<FacilityId>) -> Unit) {
    dslContext.transaction { _ ->
      val facilityIds =
          dslContext
              .select(FACILITIES.ID)
              .from(FACILITIES)
              .where(FACILITIES.IDLE_AFTER_TIME.le(clock.instant()))
              .and(FACILITIES.IDLE_SINCE_TIME.isNull)
              .forUpdate()
              .skipLocked()
              .fetch(FACILITIES.ID)
              .filterNotNull()
              .toSet()

      if (facilityIds.isNotEmpty()) {
        log.info("Found newly idle facilities: $facilityIds")

        dslContext
            .update(FACILITIES)
            .setNull(FACILITIES.IDLE_AFTER_TIME)
            .set(FACILITIES.IDLE_SINCE_TIME, clock.instant())
            .where(FACILITIES.ID.`in`(facilityIds))
            .execute()

        func(facilityIds)
      }
    }
  }
}
