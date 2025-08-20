package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.model.NewFacilityModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.SubLocationInUseException
import com.terraformation.backend.db.SubLocationNameExistsException
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SubLocationsRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.springframework.security.access.AccessDeniedException

internal class FacilityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val eventPublisher = TestEventPublisher()
  private lateinit var store: FacilityStore

  private lateinit var facilityId: FacilityId
  private lateinit var organizationId: OrganizationId
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
            subLocationsDao,
        )

    every { config.dailyTasks } returns TerrawareServerConfig.DailyTasksConfig()
    every { user.canCreateFacility(any()) } returns true
    every { user.canCreateSubLocation(any()) } returns true
    every { user.canDeleteSubLocation(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadSubLocation(any()) } returns true
    every { user.canUpdateFacility(any()) } returns true
    every { user.canUpdateSubLocation(any()) } returns true
    every { user.canUpdateTimeseries(any()) } returns true

    timeZone = ZoneId.of("Pacific/Honolulu")
    organizationId = insertOrganization()
    facilityId = insertFacility()
  }

  @Test
  fun `createSubLocation inserts correct values`() {
    val subLocationId = store.createSubLocation(facilityId, "Location")

    val expected =
        SubLocationsRow(
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = subLocationId,
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = "Location",
        )

    val actual = subLocationsDao.fetchOneById(subLocationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `createSubLocation throws exception if user lacks permission`() {
    every { user.canCreateSubLocation(facilityId) } returns false

    assertThrows<AccessDeniedException> { store.createSubLocation(facilityId, "Location") }
  }

  @Test
  fun `createSubLocation throws exception if sub-location name already in use`() {
    insertSubLocation(name = "New name")

    assertThrows<SubLocationNameExistsException> { store.createSubLocation(facilityId, "New name") }
  }

  @Test
  fun `fetchSubLocations returns values the user has permission to see`() {
    val subLocationId = insertSubLocation()
    val otherId = insertSubLocation()
    val invisibleId = insertSubLocation()

    every { user.canReadSubLocation(invisibleId) } returns false

    val expected = setOf(subLocationId, otherId)

    val actual = store.fetchSubLocations(facilityId).map { it.id }.toSet()

    assertEquals(expected, actual)
  }

  @Test
  fun `deleteSubLocation deletes sub-location with inactive accessions`() {
    val subLocationId = insertSubLocation()
    val accessionId =
        insertAccession(
            AccessionsRow(stateId = AccessionState.UsedUp, subLocationId = subLocationId)
        )

    store.deleteSubLocation(subLocationId)

    assertEquals(
        emptyList<SubLocationsRow>(),
        subLocationsDao.fetchByFacilityId(facilityId),
        "Should have deleted sub-location",
    )
    assertNull(
        accessionsDao.fetchOneById(accessionId)!!.subLocationId,
        "Should have cleared accession sub-location ID",
    )
  }

  @Test
  fun `deleteSubLocation throws exception if sub-location has active accessions`() {
    val subLocationId = insertSubLocation()

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
          .set(SUB_LOCATION_ID, subLocationId)
          .execute()
    }

    assertThrows<SubLocationInUseException> { store.deleteSubLocation(subLocationId) }
  }

  @Test
  fun `deleteSubLocation throws exception if user lacks permission`() {
    val subLocationId = insertSubLocation()

    every { user.canDeleteSubLocation(subLocationId) } returns false

    assertThrows<AccessDeniedException> { store.deleteSubLocation(subLocationId) }
  }

  @Test
  fun `updateSubLocation updates correct values`() {
    val otherUserId = insertUser()
    val subLocationId = insertSubLocation(createdBy = otherUserId)

    val newTime = Instant.EPOCH.plusSeconds(30)
    clock.instant = newTime

    store.updateSubLocation(subLocationId, "New Name")

    val expected =
        SubLocationsRow(
            createdBy = otherUserId,
            createdTime = Instant.EPOCH,
            id = subLocationId,
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = newTime,
            name = "New Name",
        )

    val actual = subLocationsDao.fetchOneById(subLocationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `updateSubLocation throws exception if user lacks permission`() {
    val subLocationId = insertSubLocation()

    every { user.canUpdateSubLocation(subLocationId) } returns false

    assertThrows<AccessDeniedException> { store.updateSubLocation(subLocationId, "New Name") }
  }

  @Test
  fun `updateSubLocation throws exception if new name is already in use`() {
    insertSubLocation(name = "Existing name")
    val otherSubLocationId = insertSubLocation(name = "New name")

    assertThrows<SubLocationNameExistsException> {
      store.updateSubLocation(otherSubLocationId, "Existing name")
    }
  }

  @Test
  fun `updateLastTimeseriesTimes resets idle timestamps`() {
    val deviceId = insertDevice()

    val initial = facilitiesDao.fetchOneById(facilityId)!!
    facilitiesDao.update(
        initial.copy(lastTimeseriesTime = null, idleAfterTime = null, idleSinceTime = Instant.EPOCH)
    )

    val expected =
        initial.copy(
            lastTimeseriesTime = clock.instant(),
            idleAfterTime = clock.instant().plus(30, ChronoUnit.MINUTES),
            idleSinceTime = null,
        )

    store.updateLastTimeseriesTimes(listOf(deviceId))

    val actual = facilitiesDao.fetchOneById(facilityId)
    assertEquals(expected, actual)
  }

  @Test
  fun `withIdleFacilities detects newly-idle facilities`() {
    clock.instant = Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val facilityIds =
        setOf(
            insertFacility(idleAfterTime = Instant.EPOCH),
            insertFacility(idleAfterTime = Instant.EPOCH),
        )

    val actual = mutableSetOf<FacilityId>()

    store.withIdleFacilities { actual.addAll(it) }

    assertEquals(facilityIds, actual)
  }

  @Test
  fun `withIdleFacilities does not repeat previously-idle facilities`() {
    clock.instant = Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val initial = facilitiesDao.fetchOneById(facilityId)!!
    facilitiesDao.update(
        initial.copy(lastTimeseriesTime = Instant.EPOCH, idleAfterTime = clock.instant())
    )

    store.withIdleFacilities {}

    store.withIdleFacilities { fail("Facilities should have already been marked idle: $it") }
  }

  @Test
  fun `withIdleFacilities repeats previously-idle facilities if handler throws exception`() {
    clock.instant = Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val initial = facilitiesDao.fetchOneById(facilityId)!!
    facilitiesDao.update(
        initial.copy(lastTimeseriesTime = Instant.EPOCH, idleAfterTime = clock.instant())
    )

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
  fun `create also creates default sub-locations`() {
    val model =
        store.create(
            NewFacilityModel(
                name = "Test",
                organizationId = organizationId,
                type = FacilityType.SeedBank,
            )
        )

    val expected =
        listOf(
            "Freezer 1",
            "Freezer 2",
            "Freezer 3",
            "Refrigerator 1",
            "Refrigerator 2",
            "Refrigerator 3",
        )

    val actual = store.fetchSubLocations(model.id).map { it.name!! }.sorted()

    assertEquals(expected, actual)
  }

  @Test
  fun `create creates sub-locations named by caller`() {
    val model =
        store.create(
            NewFacilityModel(
                name = "Test",
                organizationId = organizationId,
                subLocationNames = setOf("SL1", "SL2"),
                type = FacilityType.SeedBank,
            )
        )
    val subLocations = store.fetchSubLocations(model.id)

    assertEquals(
        setOf(
            SubLocationsRow(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = model.id,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                name = "SL1",
            ),
            SubLocationsRow(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = model.id,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                name = "SL2",
            ),
        ),
        subLocations.map { it.copy(id = null) }.toSet(),
    )
  }

  @Test
  fun `create only creates default sub-locations if requested by caller`() {
    val model =
        store.create(
            NewFacilityModel(
                name = "Test",
                organizationId = organizationId,
                subLocationNames = emptySet(),
                type = FacilityType.SeedBank,
            )
        )
    val subLocations = store.fetchSubLocations(model.id)

    assertEquals(emptyList<SubLocationsRow>(), subLocations)
  }

  @Test
  fun `create only creates default sub-locations for seed banks`() {
    val model =
        store.create(
            NewFacilityModel(
                name = "Test",
                organizationId = organizationId,
                subLocationNames = emptySet(),
                type = FacilityType.Desalination,
            )
        )
    val subLocations = store.fetchSubLocations(model.id)

    assertEquals(emptyList<SubLocationsRow>(), subLocations)
  }

  @Test
  fun `create populates all fields`() {
    val model =
        store.create(
            NewFacilityModel(
                buildCompletedDate = LocalDate.of(2023, 1, 1),
                buildStartedDate = LocalDate.of(2022, 1, 1),
                capacity = 50,
                description = "Description",
                name = "Test",
                maxIdleMinutes = 123,
                operationStartedDate = LocalDate.of(2023, 2, 2),
                organizationId = organizationId,
                subLocationNames = emptySet(),
                timeZone = timeZone,
                type = FacilityType.SeedBank,
            )
        )

    val expected =
        FacilitiesRow(
            buildCompletedDate = LocalDate.of(2023, 1, 1),
            buildStartedDate = LocalDate.of(2022, 1, 1),
            capacity = 50,
            connectionStateId = FacilityConnectionState.NotConnected,
            createdBy = user.userId,
            createdTime = clock.instant(),
            description = "Description",
            facilityNumber = 2,
            id = model.id,
            maxIdleMinutes = 123,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = "Test",
            nextNotificationTime =
                ZonedDateTime.of(LocalDate.EPOCH, config.dailyTasks.startTime, timeZone)
                    .toInstant(),
            operationStartedDate = LocalDate.of(2023, 2, 2),
            organizationId = organizationId,
            timeZone = timeZone,
            typeId = FacilityType.SeedBank,
        )

    val actual = facilitiesDao.fetchOneById(model.id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `create uses next facility number for facility type`() {
    insertFacility(type = FacilityType.Nursery, facilityNumber = 1)
    insertFacility(type = FacilityType.Nursery, facilityNumber = 2)
    insertFacility(type = FacilityType.SeedBank, facilityNumber = 2)
    insertFacility(type = FacilityType.SeedBank, facilityNumber = 3)

    val model =
        store.create(
            NewFacilityModel(
                name = "Test",
                organizationId = organizationId,
                type = FacilityType.Nursery,
            )
        )

    assertEquals(3, facilitiesDao.fetchOneById(model.id)?.facilityNumber, "Facility number")
  }

  @Test
  fun `create uses organization time zone if facility time zone not set`() {
    organizationsDao.update(
        organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = timeZone)
    )

    val model =
        store.create(
            NewFacilityModel(
                name = "Test",
                organizationId = organizationId,
                type = FacilityType.Nursery,
            )
        )

    assertNull(model.timeZone, "Facility time zone should be null")
    assertEquals(
        ZonedDateTime.of(LocalDate.EPOCH, config.dailyTasks.startTime, timeZone).toInstant(),
        model.nextNotificationTime,
    )
  }

  @Test
  fun `create uses UTC if facility and organization time zones not set`() {
    val model =
        store.create(
            NewFacilityModel(
                name = "Test",
                organizationId = organizationId,
                type = FacilityType.Nursery,
            )
        )

    assertNull(model.timeZone, "Facility time zone should be null")
    assertEquals(
        ZonedDateTime.of(LocalDate.EPOCH, config.dailyTasks.startTime, ZoneOffset.UTC).toInstant(),
        model.nextNotificationTime,
    )
  }

  @Test
  fun `create throws exception if no permission to create facilities`() {
    every { user.canCreateFacility(any()) } returns false
    every { user.canReadOrganization(any()) } returns true

    assertThrows<AccessDeniedException> {
      store.create(
          NewFacilityModel(
              name = "Test",
              organizationId = organizationId,
              type = FacilityType.SeedBank,
          )
      )
    }
  }

  @Test
  fun `update updates all editable fields`() {
    val otherTimeZone = ZoneId.of("Europe/Paris")

    val otherOrganizationId = insertOrganization()
    insertOrganizationUser(organizationId = otherOrganizationId, role = Role.Admin)

    val initial =
        store.create(
            NewFacilityModel(
                buildCompletedDate = LocalDate.of(2023, 1, 1),
                buildStartedDate = LocalDate.of(2022, 1, 1),
                capacity = 50,
                description = "Initial description",
                name = "Initial name",
                maxIdleMinutes = 1,
                operationStartedDate = LocalDate.of(2023, 2, 2),
                organizationId = organizationId,
                subLocationNames = emptySet(),
                timeZone = timeZone,
                type = FacilityType.Nursery,
            )
        )

    clock.instant = Instant.ofEpochSecond(5)

    val modified =
        initial.copy(
            buildCompletedDate = LocalDate.of(2023, 3, 1),
            buildStartedDate = LocalDate.of(2022, 3, 1),
            capacity = 99,
            connectionState = FacilityConnectionState.Configured,
            createdTime = Instant.ofEpochSecond(50),
            description = "New description",
            facilityNumber = 12345,
            lastTimeseriesTime = Instant.EPOCH,
            maxIdleMinutes = 2,
            modifiedTime = Instant.ofEpochSecond(50),
            name = "New name",
            operationStartedDate = LocalDate.of(2023, 3, 2),
            organizationId = otherOrganizationId,
            timeZone = otherTimeZone,
            type = FacilityType.SeedBank,
        )

    store.update(modified)

    val expected =
        FacilitiesRow(
            buildCompletedDate = modified.buildCompletedDate,
            buildStartedDate = modified.buildStartedDate,
            capacity = modified.capacity,
            connectionStateId = initial.connectionState,
            createdBy = user.userId,
            createdTime = initial.createdTime,
            description = modified.description,
            facilityNumber = 1,
            id = initial.id,
            maxIdleMinutes = modified.maxIdleMinutes,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = modified.name,
            nextNotificationTime =
                ZonedDateTime.of(
                        LocalDate.EPOCH.plusDays(1),
                        config.dailyTasks.startTime,
                        otherTimeZone,
                    )
                    .toInstant(),
            operationStartedDate = modified.operationStartedDate,
            organizationId = initial.organizationId,
            timeZone = modified.timeZone,
            typeId = initial.type,
        )

    val actual = facilitiesDao.fetchOneById(initial.id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `update uses organization time zone if facility time zone is cleared`() {
    val orgTimeZone = ZoneId.of("Asia/Calcutta")
    organizationsDao.update(
        organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = orgTimeZone)
    )

    store.update(store.fetchOneById(facilityId).copy(timeZone = null))

    val expected =
        ZonedDateTime.of(LocalDate.EPOCH.plusDays(1), config.dailyTasks.startTime, orgTimeZone)
            .toInstant()
    val actual = store.fetchOneById(facilityId).nextNotificationTime

    assertEquals(expected, actual)
  }

  @Test
  fun `update publishes event if time zone is changed`() {
    val otherTimeZone = ZoneId.of("Asia/Shanghai")

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
        facilityId,
        FacilityConnectionState.NotConnected,
        FacilityConnectionState.Connected,
    )

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
          facilityId,
          FacilityConnectionState.NotConnected,
          FacilityConnectionState.Connected,
      )
    }
  }

  @Test
  fun `updateConnectionState throws exception if facility is not in expected state`() {
    assertThrows<IllegalStateException> {
      store.updateConnectionState(
          facilityId,
          FacilityConnectionState.Connected,
          FacilityConnectionState.Configured,
      )
    }
  }

  @Test
  fun `fetchOneById throws exception if no permission to read facility`() {
    every { user.canReadFacility(facilityId) } returns false

    assertThrows<FacilityNotFoundException> { store.fetchOneById(facilityId) }
  }

  @Test
  fun `withNotificationsDue ignores facilities that are not yet scheduled`() {
    val expected = setOf(facilityId)
    val actual = mutableSetOf<FacilityId>()

    insertFacility(nextNotificationTime = clock.instant().plusSeconds(1))

    store.withNotificationsDue { actual.add(it.id) }

    assertEquals(expected, actual)
  }

  @Test
  fun `withNotificationsDue rolls back and continues to next facility on exception`() {
    lateinit var notificationId: NotificationId
    val rolledBackFacilityId = inserted.facilityId
    insertFacility()

    store.withNotificationsDue { facility ->
      notificationId = insertNotification()
      if (facility.id == rolledBackFacilityId) {
        throw Exception("I have failed")
      }
    }

    val expectedNotifications = listOf(notificationId)
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
        "Next notification time",
    )
  }
}
