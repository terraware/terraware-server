package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.StorageLocationInUseException
import com.terraformation.backend.db.StorageLocationNameExistsException
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.pojos.StorageLocationsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.springframework.security.access.AccessDeniedException

internal class FacilityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(FACILITIES, STORAGE_LOCATIONS)

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val eventPublisher = TestEventPublisher()
  private lateinit var store: FacilityStore

  private val storageLocationId = StorageLocationId(1000)
  private lateinit var timeZone: ZoneId

  @BeforeEach
  fun setUp() {
    store =
        FacilityStore(
            clock,
            config,
            dslContext,
            eventPublisher,
            facilitiesDao,
            Messages(),
            organizationsDao,
            storageLocationsDao)

    every { config.dailyTasks } returns TerrawareServerConfig.DailyTasksConfig()
    every { user.canCreateFacility(any()) } returns true
    every { user.canCreateStorageLocation(any()) } returns true
    every { user.canDeleteStorageLocation(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadStorageLocation(any()) } returns true
    every { user.canUpdateFacility(any()) } returns true
    every { user.canUpdateStorageLocation(any()) } returns true
    every { user.canUpdateTimeseries(any()) } returns true

    timeZone = insertTimeZone()
    insertSiteData()
  }

  @Test
  fun `createStorageLocation inserts correct values`() {
    val storageLocationId =
        store.createStorageLocation(facilityId, "Location", StorageCondition.Freezer)

    val expected =
        StorageLocationsRow(
            conditionId = StorageCondition.Freezer,
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = storageLocationId,
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = "Location",
        )

    val actual = storageLocationsDao.fetchOneById(storageLocationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `createStorageLocation throws exception if user lacks permission`() {
    every { user.canCreateStorageLocation(facilityId) } returns false

    assertThrows<AccessDeniedException> {
      store.createStorageLocation(facilityId, "Location", StorageCondition.Freezer)
    }
  }

  @Test
  fun `createStorageLocation throws exception if storage location name already in use`() {
    insertStorageLocation(500, name = "New name")

    assertThrows<StorageLocationNameExistsException> {
      store.createStorageLocation(facilityId, "New name", StorageCondition.Freezer)
    }
  }

  @Test
  fun `fetchStorageLocations returns values the user has permission to see`() {
    val otherId = StorageLocationId(1001)
    val invisibleId = StorageLocationId(1002)
    insertStorageLocation(storageLocationId)
    insertStorageLocation(otherId)
    insertStorageLocation(invisibleId)

    every { user.canReadStorageLocation(invisibleId) } returns false

    val expected = setOf(storageLocationId, otherId)

    val actual = store.fetchStorageLocations(facilityId).map { it.id }.toSet()

    assertEquals(expected, actual)
  }

  @Test
  fun `deleteStorageLocation deletes storage location with inactive accessions`() {
    insertStorageLocation(storageLocationId)
    val accessionId =
        insertAccession(
            AccessionsRow(stateId = AccessionState.UsedUp, storageLocationId = storageLocationId))

    store.deleteStorageLocation(storageLocationId)

    assertEquals(
        emptyList<StorageLocationsRow>(),
        storageLocationsDao.fetchByFacilityId(facilityId),
        "Should have deleted storage location")
    assertNull(
        accessionsDao.fetchOneById(accessionId)!!.storageLocationId,
        "Should have cleared accession storage location ID")
  }

  @Test
  fun `deleteStorageLocation throws exception if storage location has active accessions`() {
    insertStorageLocation(storageLocationId)

    with(ACCESSIONS) {
      dslContext
          .insertInto(ACCESSIONS)
          .set(CREATED_BY, user.userId)
          .set(CREATED_TIME, clock.instant())
          .set(DATA_SOURCE_ID, DataSource.Web)
          .set(FACILITY_ID, facilityId)
          .set(MODIFIED_BY, user.userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(STATE_ID, AccessionState.InStorage)
          .set(STORAGE_LOCATION_ID, storageLocationId)
          .execute()
    }

    assertThrows<StorageLocationInUseException> { store.deleteStorageLocation(storageLocationId) }
  }

  @Test
  fun `deleteStorageLocation throws exception if user lacks permission`() {
    insertStorageLocation(storageLocationId)

    every { user.canDeleteStorageLocation(storageLocationId) } returns false

    assertThrows<AccessDeniedException> { store.deleteStorageLocation(storageLocationId) }
  }

  @Test
  fun `updateStorageLocation updates correct values`() {
    val otherUserId = UserId(10)
    insertUser(otherUserId)
    insertStorageLocation(
        storageLocationId, condition = StorageCondition.Refrigerator, createdBy = otherUserId)

    val newTime = Instant.EPOCH.plusSeconds(30)
    clock.instant = newTime

    store.updateStorageLocation(storageLocationId, "New Name", StorageCondition.Freezer)

    val expected =
        StorageLocationsRow(
            conditionId = StorageCondition.Freezer,
            createdBy = otherUserId,
            createdTime = Instant.EPOCH,
            id = storageLocationId,
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = newTime,
            name = "New Name",
        )

    val actual = storageLocationsDao.fetchOneById(storageLocationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `updateStorageLocation throws exception if user lacks permission`() {
    insertStorageLocation(storageLocationId)

    every { user.canUpdateStorageLocation(storageLocationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateStorageLocation(storageLocationId, "New Name", StorageCondition.Freezer)
    }
  }

  @Test
  fun `updateStorageLocation throws exception if new name is already in use`() {
    val otherStorageLocationId = StorageLocationId(2)
    insertStorageLocation(storageLocationId, name = "Existing name")
    insertStorageLocation(otherStorageLocationId, name = "New name")

    assertThrows<StorageLocationNameExistsException> {
      store.updateStorageLocation(otherStorageLocationId, "Existing name", StorageCondition.Freezer)
    }
  }

  @Test
  fun `updateLastTimeseriesTimes resets idle timestamps`() {
    val deviceId = DeviceId(1)
    insertDevice(deviceId)

    val initial = facilitiesDao.fetchOneById(facilityId)!!
    facilitiesDao.update(
        initial.copy(
            lastTimeseriesTime = null, idleAfterTime = null, idleSinceTime = Instant.EPOCH))

    val expected =
        initial.copy(
            lastTimeseriesTime = clock.instant(),
            idleAfterTime = clock.instant().plus(30, ChronoUnit.MINUTES),
            idleSinceTime = null)

    store.updateLastTimeseriesTimes(listOf(deviceId))

    val actual = facilitiesDao.fetchOneById(facilityId)
    assertEquals(expected, actual)
  }

  @Test
  fun `withIdleFacilities detects newly-idle facilities`() {
    clock.instant = Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val facilityIds = setOf(FacilityId(101), FacilityId(102))
    facilityIds.forEach { id -> insertFacility(id, idleAfterTime = Instant.EPOCH) }

    val actual = mutableSetOf<FacilityId>()

    store.withIdleFacilities { actual.addAll(it) }

    assertEquals(facilityIds, actual)
  }

  @Test
  fun `withIdleFacilities does not repeat previously-idle facilities`() {
    clock.instant = Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val initial = facilitiesDao.fetchOneById(facilityId)!!
    facilitiesDao.update(
        initial.copy(lastTimeseriesTime = Instant.EPOCH, idleAfterTime = clock.instant()))

    store.withIdleFacilities {}

    store.withIdleFacilities { fail("Facilities should have already been marked idle: $it") }
  }

  @Test
  fun `withIdleFacilities repeats previously-idle facilities if handler throws exception`() {
    clock.instant = Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val initial = facilitiesDao.fetchOneById(facilityId)!!
    facilitiesDao.update(
        initial.copy(lastTimeseriesTime = Instant.EPOCH, idleAfterTime = clock.instant()))

    try {
      store.withIdleFacilities { throw Exception("Failing callback function") }
    } catch (e: Exception) {
      // Expected
    }

    val actual = mutableSetOf<FacilityId>()

    store.withIdleFacilities { actual.addAll(it) }

    assertEquals(setOf(facilityId), actual)
  }

  @Test
  fun `create also creates default storage locations`() {
    val model =
        store.create(organizationId, FacilityType.SeedBank, "Test", storageLocationNames = null)

    val expected =
        mapOf(
            StorageCondition.Freezer to listOf("Freezer 1", "Freezer 2", "Freezer 3"),
            StorageCondition.Refrigerator to
                listOf("Refrigerator 1", "Refrigerator 2", "Refrigerator 3"))

    val storageLocations = store.fetchStorageLocations(model.id)
    val actual = storageLocations.sortedBy { it.name }.groupBy({ it.conditionId }, { it.name })

    assertEquals(expected, actual)
  }

  @Test
  fun `create creates storage locations named by caller`() {
    val model =
        store.create(
            organizationId,
            FacilityType.SeedBank,
            "Test",
            storageLocationNames = setOf("SL1", "SL2"))
    val storageLocations = store.fetchStorageLocations(model.id)

    assertEquals(
        setOf(
            StorageLocationsRow(
                conditionId = StorageCondition.Freezer,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = model.id,
                id = StorageLocationId(1),
                modifiedTime = Instant.EPOCH,
                modifiedBy = user.userId,
                name = "SL1",
            ),
            StorageLocationsRow(
                conditionId = StorageCondition.Freezer,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = model.id,
                id = StorageLocationId(2),
                modifiedTime = Instant.EPOCH,
                modifiedBy = user.userId,
                name = "SL2",
            ),
        ),
        storageLocations.toSet())
  }

  @Test
  fun `create only creates default storage locations if requested by caller`() {
    val model =
        store.create(
            organizationId, FacilityType.SeedBank, "Test", storageLocationNames = emptySet())
    val storageLocations = store.fetchStorageLocations(model.id)

    assertEquals(emptyList<StorageLocationsRow>(), storageLocations)
  }

  @Test
  fun `create only creates default storage locations for seed banks`() {
    val model =
        store.create(
            organizationId, FacilityType.Desalination, "Test", storageLocationNames = emptySet())
    val storageLocations = store.fetchStorageLocations(model.id)

    assertEquals(emptyList<StorageLocationsRow>(), storageLocations)
  }

  @Test
  fun `create populates all fields`() {
    val model =
        store.create(
            organizationId, FacilityType.SeedBank, "Test", "Description", 123, emptySet(), timeZone)

    val expected =
        FacilitiesRow(
            connectionStateId = FacilityConnectionState.NotConnected,
            createdBy = user.userId,
            createdTime = clock.instant(),
            description = "Description",
            id = model.id,
            maxIdleMinutes = 123,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = "Test",
            nextNotificationTime =
                ZonedDateTime.of(LocalDate.EPOCH, config.dailyTasks.startTime, timeZone)
                    .toInstant(),
            organizationId = organizationId,
            timeZone = timeZone,
            typeId = FacilityType.SeedBank,
        )

    val actual = facilitiesDao.fetchOneById(model.id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `create uses organization time zone if facility time zone not set`() {
    organizationsDao.update(
        organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = timeZone))

    val model = store.create(organizationId, FacilityType.Nursery, "Test")

    assertNull(model.timeZone, "Facility time zone should be null")
    assertEquals(
        ZonedDateTime.of(LocalDate.EPOCH, config.dailyTasks.startTime, timeZone).toInstant(),
        model.nextNotificationTime)
  }

  @Test
  fun `create uses UTC if facility and organization time zones not set`() {
    val model = store.create(organizationId, FacilityType.Nursery, "Test")

    assertNull(model.timeZone, "Facility time zone should be null")
    assertEquals(
        ZonedDateTime.of(LocalDate.EPOCH, config.dailyTasks.startTime, ZoneOffset.UTC).toInstant(),
        model.nextNotificationTime)
  }

  @Test
  fun `create throws exception if no permission to create facilities`() {
    every { user.canCreateFacility(any()) } returns false
    every { user.canReadOrganization(any()) } returns true

    assertThrows<AccessDeniedException> {
      store.create(organizationId, FacilityType.SeedBank, "Test")
    }
  }

  @Test
  fun `update updates all editable fields`() {
    val otherOrganizationId = OrganizationId(10)
    val otherTimeZone = insertTimeZone("Europe/Paris")

    insertOrganization(otherOrganizationId)
    insertOrganizationUser(organizationId = otherOrganizationId, role = Role.Admin)

    val initial =
        store.create(
            description = "Initial description",
            maxIdleMinutes = 1,
            name = "Initial name",
            organizationId = organizationId,
            storageLocationNames = emptySet(),
            timeZone = timeZone,
            type = FacilityType.Nursery,
        )

    clock.instant = Instant.ofEpochSecond(5)

    val modified =
        initial.copy(
            connectionState = FacilityConnectionState.Configured,
            createdTime = Instant.ofEpochSecond(50),
            description = "New description",
            lastTimeseriesTime = Instant.EPOCH,
            maxIdleMinutes = 2,
            modifiedTime = Instant.ofEpochSecond(50),
            name = "New name",
            organizationId = otherOrganizationId,
            timeZone = otherTimeZone,
            type = FacilityType.SeedBank,
        )

    store.update(modified)

    val expected =
        FacilitiesRow(
            connectionStateId = initial.connectionState,
            createdBy = user.userId,
            createdTime = initial.createdTime,
            description = modified.description,
            id = initial.id,
            maxIdleMinutes = modified.maxIdleMinutes,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = modified.name,
            nextNotificationTime =
                ZonedDateTime.of(
                        LocalDate.EPOCH.plusDays(1), config.dailyTasks.startTime, otherTimeZone)
                    .toInstant(),
            organizationId = initial.organizationId,
            timeZone = modified.timeZone,
            typeId = initial.type,
        )

    val actual = facilitiesDao.fetchOneById(initial.id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `update uses organization time zone if facility time zone is cleared`() {
    val orgTimeZone = insertTimeZone("Asia/Calcutta")
    organizationsDao.update(
        organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = orgTimeZone))

    store.update(store.fetchOneById(facilityId).copy(timeZone = null))

    val expected =
        ZonedDateTime.of(LocalDate.EPOCH.plusDays(1), config.dailyTasks.startTime, orgTimeZone)
            .toInstant()
    val actual = store.fetchOneById(facilityId).nextNotificationTime

    assertEquals(expected, actual)
  }

  @Test
  fun `update publishes event if time zone is changed`() {
    val otherTimeZone = insertTimeZone("Asia/Shanghai")

    store.update(store.fetchOneById(facilityId).copy(timeZone = otherTimeZone))

    eventPublisher.assertEventPublished { event ->
      event is FacilityTimeZoneChangedEvent &&
          event.facility.id == facilityId &&
          event.facility.timeZone == otherTimeZone
    }
  }

  @Test
  fun `updateConnectionState transitions expected state to new state`() {
    val initial = facilitiesDao.fetchOneById(facilityId)!!

    val now = Instant.EPOCH + Duration.ofDays(1)
    clock.instant = now

    store.updateConnectionState(
        facilityId, FacilityConnectionState.NotConnected, FacilityConnectionState.Connected)

    val expected =
        initial.copy(connectionStateId = FacilityConnectionState.Connected, modifiedTime = now)
    val actual = facilitiesDao.fetchOneById(facilityId)

    assertEquals(expected, actual)
  }

  @Test
  fun `updateConnectionState throws exception if no permission to update facility`() {
    every { user.canUpdateFacility(facilityId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateConnectionState(
          facilityId, FacilityConnectionState.NotConnected, FacilityConnectionState.Connected)
    }
  }

  @Test
  fun `updateConnectionState throws exception if facility is not in expected state`() {
    assertThrows<IllegalStateException> {
      store.updateConnectionState(
          facilityId, FacilityConnectionState.Connected, FacilityConnectionState.Configured)
    }
  }

  @Test
  fun `fetchOneById throws exception if no permission to read facility`() {
    every { user.canReadFacility(facilityId) } returns false

    assertThrows<FacilityNotFoundException> { store.fetchOneById(facilityId) }
  }

  @Test
  fun `withNotificationsDue ignores facilities that are not yet scheduled`() {
    val notDueFacilityId = FacilityId(1001)
    insertFacility(notDueFacilityId, nextNotificationTime = clock.instant().plusSeconds(1))

    val expected = setOf(facilityId)
    val actual = mutableSetOf<FacilityId>()

    store.withNotificationsDue { actual.add(it.id) }

    assertEquals(expected, actual)
  }

  @Test
  fun `withNotificationsDue rolls back and continues to next facility on exception`() {
    val otherFacilityId = FacilityId(facilityId.value + 1)
    insertFacility(otherFacilityId)

    store.withNotificationsDue { facility ->
      insertNotification(NotificationId(facility.id.value))
      if (facility.id == facilityId) {
        throw Exception("I have failed")
      }
    }

    val expectedNotifications = listOf(NotificationId(otherFacilityId.value))
    val actualNotifications = notificationsDao.findAll().map { it.id }

    assertEquals(expectedNotifications, actualNotifications)
  }

  @Test
  fun `updateNotificationTimes calculates correct next notification time`() {
    val timeZone = ZoneId.of("Pacific/Honolulu")
    facilitiesDao.update(facilitiesDao.fetchOneById(facilityId)!!.copy(timeZone = timeZone))

    clock.instant = ZonedDateTime.of(1977, 8, 9, 8, 15, 0, 0, timeZone).toInstant()

    val facility = store.fetchOneById(facilityId)
    val todayAtFacility = LocalDate.ofInstant(clock.instant(), timeZone)

    store.updateNotificationTimes(facility.copy(lastNotificationDate = todayAtFacility))

    val updatedRow = facilitiesDao.fetchOneById(facilityId)!!

    assertEquals(todayAtFacility, updatedRow.lastNotificationDate, "Last notification date")
    assertEquals(
        todayAtFacility.plusDays(1).atTime(0, 1).atZone(timeZone).toInstant(),
        updatedRow.nextNotificationTime,
        "Next notification time")
  }
}
