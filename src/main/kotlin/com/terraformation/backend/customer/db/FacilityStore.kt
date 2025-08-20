package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.NewFacilityModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.FacilityAlreadyConnectedException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.SubLocationInUseException
import com.terraformation.backend.db.SubLocationNameExistsException
import com.terraformation.backend.db.SubLocationNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.daos.SubLocationsDao
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SubLocationsRow
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.model.activeValues
import com.terraformation.backend.time.atNext
import jakarta.inject.Named
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

/** Permission-aware accessors for facility information. */
@Named
class FacilityStore(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilitiesDao: FacilitiesDao,
    private val messages: Messages,
    private val organizationsDao: OrganizationsDao,
    private val subLocationsDao: SubLocationsDao,
) {
  private val log = perClassLogger()

  fun fetchOneById(facilityId: FacilityId): FacilityModel {
    requirePermissions { readFacility(facilityId) }

    return facilitiesDao.fetchOneById(facilityId)?.toModel()
        ?: throw FacilityNotFoundException(facilityId)
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

  fun fetchByOrganizationId(organizationId: OrganizationId): List<FacilityModel> {
    requirePermissions { readOrganization(organizationId) }

    val user = currentUser()

    return facilitiesDao
        .fetchByOrganizationId(organizationId)
        .map { it.toModel() }
        .filter { user.canReadFacility(it.id) }
  }

  /** Returns the facilities that are being used by a project. */
  fun fetchByProjectId(projectId: ProjectId): List<FacilityModel> {
    requirePermissions { readProject(projectId) }

    return dslContext
        .select(FACILITIES.asterisk())
        .from(FACILITIES)
        .where(
            FACILITIES.ORGANIZATION_ID.eq(
                DSL.select(PROJECTS.ORGANIZATION_ID).from(PROJECTS).where(PROJECTS.ID.eq(projectId))
            )
        )
        .and(
            DSL.or(
                DSL.exists(
                    DSL.selectOne()
                        .from(BATCHES)
                        .where(BATCHES.FACILITY_ID.eq(FACILITIES.ID))
                        .and(BATCHES.PROJECT_ID.eq(projectId))
                ),
                DSL.exists(
                    DSL.selectOne()
                        .from(ACCESSIONS)
                        .where(ACCESSIONS.FACILITY_ID.eq(FACILITIES.ID))
                        .and(ACCESSIONS.PROJECT_ID.eq(projectId))
                ),
            )
        )
        .fetch { FacilityModel(it) }
  }

  /**
   * Creates a new facility.
   *
   * @throws AccessDeniedException The current user does not have permission to create facilities.
   */
  fun create(newModel: NewFacilityModel): FacilityModel {
    requirePermissions { createFacility(newModel.organizationId) }

    return dslContext.transactionResult { _ ->
      // Only allow one facility to be created at a time in a given organization, to prevent a race
      // where the same facility number could be used for two concurrently-created facilities in the
      // same organization.
      dslContext
          .selectOne()
          .from(ORGANIZATIONS)
          .where(ORGANIZATIONS.ID.eq(newModel.organizationId))
          .forUpdate()
          .execute()

      val highestFacilityNumber =
          dslContext
              .select(DSL.max(FACILITIES.FACILITY_NUMBER))
              .from(FACILITIES)
              .where(FACILITIES.ORGANIZATION_ID.eq(newModel.organizationId))
              .and(FACILITIES.TYPE_ID.eq(newModel.type))
              .fetchOne()
              ?.value1() ?: 0

      val row =
          FacilitiesRow(
              buildCompletedDate = newModel.buildCompletedDate,
              buildStartedDate = newModel.buildStartedDate,
              capacity = newModel.capacity,
              connectionStateId = FacilityConnectionState.NotConnected,
              createdBy = currentUser().userId,
              createdTime = clock.instant(),
              description = newModel.description,
              facilityNumber = highestFacilityNumber + 1,
              maxIdleMinutes = newModel.maxIdleMinutes,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
              name = newModel.name,
              nextNotificationTime =
                  calculateNextNotificationTime(newModel.timeZone, newModel.organizationId),
              operationStartedDate = newModel.operationStartedDate,
              organizationId = newModel.organizationId,
              timeZone = newModel.timeZone,
              typeId = newModel.type,
          )

      facilitiesDao.insert(row)

      val savedModel = row.toModel()

      if (newModel.subLocationNames != null) {
        newModel.subLocationNames.forEach { name -> createSubLocation(savedModel.id, name) }
      } else if (newModel.type == FacilityType.SeedBank) {
        (1..3).forEach { num ->
          createSubLocation(savedModel.id, messages.refrigeratorName(num))
          createSubLocation(savedModel.id, messages.freezerName(num))
        }
      }

      savedModel
    }
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

    val updatedRow =
        existingRow.copy(
            buildCompletedDate = model.buildCompletedDate,
            buildStartedDate = model.buildStartedDate,
            capacity = model.capacity,
            description = model.description,
            maxIdleMinutes = model.maxIdleMinutes,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = model.name,
            nextNotificationTime =
                calculateNextNotificationTime(model.timeZone, model.organizationId),
            operationStartedDate = model.operationStartedDate,
            timeZone = model.timeZone,
        )

    facilitiesDao.update(updatedRow)

    if (model.timeZone != existingRow.timeZone) {
      eventPublisher.publishEvent(FacilityTimeZoneChangedEvent(updatedRow.toModel()))
    }
  }

  fun fetchSubLocation(subLocationIdId: SubLocationId): SubLocationsRow {
    requirePermissions { readSubLocation(subLocationIdId) }

    return subLocationsDao.fetchOneById(subLocationIdId)
        ?: throw SubLocationNotFoundException(subLocationIdId)
  }

  fun fetchSubLocations(facilityId: FacilityId): List<SubLocationsRow> {
    requirePermissions { readFacility(facilityId) }

    return subLocationsDao.fetchByFacilityId(facilityId).filter {
      val subLocationId = it.id
      subLocationId != null && currentUser().canReadSubLocation(subLocationId)
    }
  }

  fun createSubLocation(facilityId: FacilityId, name: String): SubLocationId {
    requirePermissions { createSubLocation(facilityId) }

    val row =
        SubLocationsRow(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            facilityId = facilityId,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
        )

    try {
      subLocationsDao.insert(row)
    } catch (e: DuplicateKeyException) {
      throw SubLocationNameExistsException(name)
    }

    return row.id ?: throw IllegalStateException("ID not present after insertion")
  }

  fun updateSubLocation(subLocationId: SubLocationId, name: String) {
    requirePermissions { updateSubLocation(subLocationId) }

    try {
      with(SUB_LOCATIONS) {
        dslContext
            .update(SUB_LOCATIONS)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .set(NAME, name)
            .where(ID.eq(subLocationId))
            .execute()
      }
    } catch (e: DuplicateKeyException) {
      throw SubLocationNameExistsException(name)
    }
  }

  /**
   * Deletes a sub-location. This will only succeed if the sub-location is not referenced by any
   * accessions.
   *
   * @throws SubLocationInUseException The sub-location is in use.
   */
  fun deleteSubLocation(subLocationId: SubLocationId) {
    requirePermissions { deleteSubLocation(subLocationId) }

    val row = fetchSubLocation(subLocationId)

    // We should be able to delete a sub-location if it has no active accessions, but it might have
    // inactive ones; we need to remove their sub-location IDs so the foreign key constraint doesn't
    // stop us from deleting the sub-location.
    dslContext.transaction { _ ->
      dslContext
          .update(ACCESSIONS)
          .set(ACCESSIONS.MODIFIED_BY, currentUser().userId)
          .set(ACCESSIONS.MODIFIED_TIME, clock.instant())
          .setNull(ACCESSIONS.SUB_LOCATION_ID)
          .where(ACCESSIONS.FACILITY_ID.eq(row.facilityId))
          .and(ACCESSIONS.SUB_LOCATION_ID.eq(subLocationId))
          .and(ACCESSIONS.STATE_ID.notIn(AccessionState.activeValues))
          .execute()

      try {
        dslContext.deleteFrom(SUB_LOCATIONS).where(SUB_LOCATIONS.ID.eq(subLocationId)).execute()
      } catch (e: DataIntegrityViolationException) {
        throw SubLocationInUseException(subLocationId)
      }
    }
  }

  /**
   * Transitions a facility's connection state.
   *
   * @throws FacilityAlreadyConnectedException The facility was already in a connected state.
   * @throws IllegalStateException The facility wasn't in the expected state. Only thrown if the
   *   expected state is not [FacilityConnectionState.NotConnected].
   */
  fun updateConnectionState(
      facilityId: FacilityId,
      expectedState: FacilityConnectionState,
      newState: FacilityConnectionState,
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
              "Facility $facilityId was in connection state $currentState, not $expectedState"
          )
          throw IllegalStateException("Facility was not in expected connection state")
        }
      }
    }
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
                          .where(DEVICES.ID.`in`(uniqueDeviceIds))
                  )
              )
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
   *   database transaction.
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
              .fetchSet(FACILITIES.ID.asNonNullable())

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

  /**
   * Runs an operation on facilities whose next notification times have arrived.
   *
   * This is concurrency-safe and will skip any facilities that are already in progress on another
   * thread or another server instance.
   *
   * @param func Operation to perform on each facility. The function will be called in a database
   *   transaction; if it throws an exception, the transaction will be rolled back and the next
   *   facility, if any, will be processed.
   */
  fun withNotificationsDue(func: (FacilityModel) -> Unit) {
    var lastFacilityId: FacilityId? = null

    do {
      val conditions =
          listOfNotNull(
              FACILITIES.NEXT_NOTIFICATION_TIME.le(clock.instant()),
              lastFacilityId?.let { FACILITIES.ID.gt(it) },
          )

      dslContext.transaction { _ ->
        val facility =
            dslContext
                .selectFrom(FACILITIES)
                .where(conditions)
                .orderBy(FACILITIES.ID)
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOne { FacilityModel(it) }

        if (facility != null) {
          // This should only be called as the system user, so bomb out if it's called as some other
          // user and would expose facility information.
          requirePermissions { readFacility(facility.id) }

          try {
            // Run the function in a nested transaction so any writes it does will be rolled back
            // if it throws an exception, but the row lock on the facility will continue to be held.
            dslContext.transaction { _ -> func(facility) }
          } catch (e: Exception) {
            log.error("Exception thrown while processing facility ${facility.id} notifications", e)

            // Fall through to advance to next facility so a broken facility doesn't stop other
            // facilities from getting notifications.
          }
        }

        lastFacilityId = facility?.id
      }
    } while (lastFacilityId != null)
  }

  fun updateNotificationTimes(facility: FacilityModel): FacilityModel {
    val nextNotificationTime =
        calculateNextNotificationTime(facility.timeZone, facility.organizationId)

    dslContext
        .update(FACILITIES)
        .set(FACILITIES.LAST_NOTIFICATION_DATE, facility.lastNotificationDate)
        .set(FACILITIES.NEXT_NOTIFICATION_TIME, nextNotificationTime)
        .where(FACILITIES.ID.eq(facility.id))
        .execute()

    return facility.copy(nextNotificationTime = nextNotificationTime)
  }

  fun getClock(facilityId: FacilityId): Clock {
    return clock.withZone(fetchEffectiveTimeZone(fetchOneById(facilityId)))
  }

  fun fetchEffectiveTimeZone(facility: FacilityModel): ZoneId {
    return fetchEffectiveTimeZone(facility.timeZone, facility.organizationId)
  }

  private fun fetchEffectiveTimeZone(timeZone: ZoneId?, organizationId: OrganizationId): ZoneId {
    return timeZone ?: organizationsDao.fetchOneById(organizationId)?.timeZone ?: ZoneOffset.UTC
  }

  private fun calculateNextNotificationTime(timeZone: ZoneId): Instant {
    return ZonedDateTime.ofInstant(clock.instant(), timeZone)
        .atNext(config.dailyTasks.startTime)
        .toInstant()
  }

  private fun calculateNextNotificationTime(
      facilityTimeZone: ZoneId?,
      organizationId: OrganizationId,
  ): Instant {
    return calculateNextNotificationTime(fetchEffectiveTimeZone(facilityTimeZone, organizationId))
  }
}
