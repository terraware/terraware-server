package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.pojos.StorageLocationsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class FacilityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock: Clock = mockk()
  private lateinit var store: FacilityStore

  private val storageLocationId = StorageLocationId(1000)
  private lateinit var timeZone: ZoneId

  @BeforeEach
  fun setUp() {
    store = FacilityStore(clock, dslContext, facilitiesDao, storageLocationsDao)

    every { clock.instant() } returns Instant.EPOCH
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
            enabled = true,
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
  fun `deleteStorageLocation throws exception if storage location is in use`() {
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

    assertThrows<DataIntegrityViolationException> { store.deleteStorageLocation(storageLocationId) }
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
    every { clock.instant() } returns newTime

    store.updateStorageLocation(storageLocationId, "New Name", StorageCondition.Freezer)

    val expected =
        StorageLocationsRow(
            conditionId = StorageCondition.Freezer,
            createdBy = otherUserId,
            createdTime = Instant.EPOCH,
            enabled = true,
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
    every { clock.instant() } returns Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val facilityIds = setOf(FacilityId(101), FacilityId(102))
    facilityIds.forEach { id -> insertFacility(id, idleAfterTime = Instant.EPOCH) }

    val actual = mutableSetOf<FacilityId>()

    store.withIdleFacilities { actual.addAll(it) }

    assertEquals(facilityIds, actual)
  }

  @Test
  fun `withIdleFacilities does not repeat previously-idle facilities`() {
    every { clock.instant() } returns Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

    val initial = facilitiesDao.fetchOneById(facilityId)!!
    facilitiesDao.update(
        initial.copy(lastTimeseriesTime = Instant.EPOCH, idleAfterTime = clock.instant()))

    store.withIdleFacilities {}

    store.withIdleFacilities { fail("Facilities should have already been marked idle: $it") }
  }

  @Test
  fun `withIdleFacilities repeats previously-idle facilities if handler throws exception`() {
    every { clock.instant() } returns Instant.EPOCH.plus(30, ChronoUnit.MINUTES)

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
        store.create(organizationId, FacilityType.SeedBank, "Test", createStorageLocations = true)

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
  fun `create only creates default storage locations if requested by caller`() {
    val model =
        store.create(organizationId, FacilityType.SeedBank, "Test", createStorageLocations = false)
    val storageLocations = store.fetchStorageLocations(model.id)

    assertEquals(emptyList<StorageLocationsRow>(), storageLocations)
  }

  @Test
  fun `create only creates default storage locations for seed banks`() {
    val model =
        store.create(
            organizationId, FacilityType.Desalination, "Test", createStorageLocations = true)
    val storageLocations = store.fetchStorageLocations(model.id)

    assertEquals(emptyList<StorageLocationsRow>(), storageLocations)
  }

  @Test
  fun `create populates all fields`() {
    val model =
        store.create(
            organizationId, FacilityType.SeedBank, "Test", "Description", 123, false, timeZone)

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
            organizationId = organizationId,
            timeZone = timeZone,
            typeId = FacilityType.SeedBank,
        )

    val actual = facilitiesDao.fetchOneById(model.id)!!

    assertEquals(expected, actual)
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
    insertOrganizationUser(organizationId = otherOrganizationId, role = Role.ADMIN)

    val initial =
        store.create(
            createStorageLocations = false,
            description = "Initial description",
            maxIdleMinutes = 1,
            name = "Initial name",
            organizationId = organizationId,
            timeZone = timeZone,
            type = FacilityType.Nursery,
        )

    every { clock.instant() } returns Instant.ofEpochSecond(5)

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
            organizationId = initial.organizationId,
            timeZone = modified.timeZone,
            typeId = initial.type,
        )

    val actual = facilitiesDao.fetchOneById(initial.id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `updateConnectionState transitions expected state to new state`() {
    val initial = facilitiesDao.fetchOneById(facilityId)!!

    val now = Instant.EPOCH + Duration.ofDays(1)
    every { clock.instant() } returns now

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
}
