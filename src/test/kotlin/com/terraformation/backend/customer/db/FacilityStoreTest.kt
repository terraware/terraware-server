package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class FacilityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock: Clock = mockk()
  private lateinit var store: FacilityStore

  private val facilityId = FacilityId(100)
  private val storageLocationId = StorageLocationId(1000)

  @BeforeEach
  fun setUp() {
    store =
        FacilityStore(
            clock, dslContext, facilitiesDao, facilityAlertRecipientsDao, storageLocationsDao)

    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreateStorageLocation(any()) } returns true
    every { user.canDeleteStorageLocation(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadStorageLocation(any()) } returns true
    every { user.canUpdateStorageLocation(any()) } returns true

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
}
