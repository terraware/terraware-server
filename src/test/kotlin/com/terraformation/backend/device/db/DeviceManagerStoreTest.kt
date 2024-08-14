package com.terraformation.backend.device.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class DeviceManagerStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val store: DeviceManagerStore by lazy {
    DeviceManagerStore(clock, deviceManagersDao, dslContext)
  }

  @BeforeEach
  fun setUp() {
    every { user.canCreateDeviceManager() } returns true
    every { user.canReadDeviceManager(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canUpdateDeviceManager(any()) } returns true
    every { user.canUpdateFacility(any()) } returns true

    insertOrganization()
  }

  @Test
  fun `fetchOneById returns row for device manager`() {
    val row = insertDeviceManager()

    val expected = deviceManagersDao.fetchOneById(row.id!!)
    val actual = store.fetchOneById(row.id!!)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchOneById throws exception if user has no read permission`() {
    val row = insertDeviceManager()

    every { user.canReadDeviceManager(row.id!!) } returns false

    assertThrows<DeviceManagerNotFoundException> { store.fetchOneById(row.id!!) }
  }

  @Test
  fun `fetchOneById throws exception if the device manager does not exist`() {
    assertThrows<DeviceManagerNotFoundException> { store.fetchOneById(DeviceManagerId(123)) }
  }

  @Test
  fun `getLockedById returns row for device manager`() {
    val row = insertDeviceManager()

    val expected = deviceManagersDao.fetchOneById(row.id!!)
    val actual = store.getLockedById(row.id!!)

    assertEquals(expected, actual)
  }

  @Test
  fun `getLockedById throws exception if user has no update permission`() {
    val row = insertDeviceManager()

    every { user.canUpdateDeviceManager(row.id!!) } returns false

    assertThrows<AccessDeniedException> { store.getLockedById(row.id!!) }
  }

  @Test
  fun `getLockedById throws exception if device manager does not exist`() {
    assertThrows<DeviceManagerNotFoundException> { store.getLockedById(DeviceManagerId(123)) }
  }

  @Test
  fun `insert inserts new row`() {
    val row = newRow()

    store.insert(row)
    assertNotNull(row.id)

    val actual = deviceManagersDao.fetchOneById(row.id!!)
    assertEquals(row, actual)
  }

  @Test
  fun `insert throws exception if user has no create permission`() {
    every { user.canCreateDeviceManager() } returns false

    assertThrows<AccessDeniedException> { store.insert(newRow()) }
  }

  @Test
  fun `insert throws exception if facility ID specified and user cannot update facility`() {
    insertFacility()

    every { user.canUpdateFacility(inserted.facilityId) } returns false

    assertThrows<AccessDeniedException> { store.insert(newRow(facilityId = inserted.facilityId)) }
  }

  @Test
  fun `update throws exception if user has no update permission`() {
    val row = insertDeviceManager()
    val initial = deviceManagersDao.fetchOneById(row.id!!)!!

    every { user.canUpdateDeviceManager(row.id!!) } returns false

    assertThrows<AccessDeniedException> { store.update(initial) }
  }

  private fun newRow(
      balenaId: Long = nextBalenaId.getAndIncrement(),
      balenaUuid: String = UUID.randomUUID().toString(),
      sensorKitId: String = "$balenaId",
      userId: UserId? = null,
      facilityId: FacilityId? = null,
  ): DeviceManagersRow {
    return DeviceManagersRow(
        balenaModifiedTime = Instant.EPOCH,
        balenaId = BalenaDeviceId(balenaId),
        balenaUuid = balenaUuid,
        createdTime = Instant.EPOCH,
        deviceName = "Device $balenaId",
        facilityId = facilityId,
        isOnline = true,
        refreshedTime = Instant.EPOCH,
        sensorKitId = sensorKitId,
        userId = userId,
    )
  }
}
