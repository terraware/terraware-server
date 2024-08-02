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

  private val deviceManagerId = DeviceManagerId(1)

  @BeforeEach
  fun setUp() {
    every { user.canCreateDeviceManager() } returns true
    every { user.canReadDeviceManager(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canUpdateDeviceManager(any()) } returns true
    every { user.canUpdateFacility(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `fetchOneById returns row for device manager`() {
    insertDeviceManager()

    val expected = deviceManagersDao.fetchOneById(deviceManagerId)!!
    val actual = store.fetchOneById(deviceManagerId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchOneById throws exception if user has no read permission`() {
    insertDeviceManager()

    every { user.canReadDeviceManager(deviceManagerId) } returns false

    assertThrows<DeviceManagerNotFoundException> { store.fetchOneById(deviceManagerId) }
  }

  @Test
  fun `fetchOneById throws exception if the device manager does not exist`() {
    assertThrows<DeviceManagerNotFoundException> { store.fetchOneById(deviceManagerId) }
  }

  @Test
  fun `getLockedById returns row for device manager`() {
    insertDeviceManager()

    val expected = deviceManagersDao.fetchOneById(deviceManagerId)!!
    val actual = store.getLockedById(deviceManagerId)

    assertEquals(expected, actual)
  }

  @Test
  fun `getLockedById throws exception if user has no update permission`() {
    insertDeviceManager()

    every { user.canUpdateDeviceManager(deviceManagerId) } returns false

    assertThrows<AccessDeniedException> { store.getLockedById(deviceManagerId) }
  }

  @Test
  fun `getLockedById throws exception if device manager does not exist`() {
    assertThrows<DeviceManagerNotFoundException> { store.getLockedById(deviceManagerId) }
  }

  @Test
  fun `insert inserts new row`() {
    val row = newRow(id = null)

    store.insert(row)
    assertNotNull(row.id)

    val actual = deviceManagersDao.fetchOneById(row.id!!)
    assertEquals(row, actual)
  }

  @Test
  fun `insert throws exception if user has no create permission`() {
    every { user.canCreateDeviceManager() } returns false

    assertThrows<AccessDeniedException> { store.insert(newRow(id = null)) }
  }

  @Test
  fun `insert throws exception if facility ID specified and user cannot update facility`() {
    every { user.canUpdateFacility(inserted.facilityId) } returns false

    assertThrows<AccessDeniedException> {
      store.insert(newRow(id = null, facilityId = inserted.facilityId))
    }
  }

  @Test
  fun `update throws exception if user has no update permission`() {
    insertDeviceManager()
    val initial = deviceManagersDao.fetchOneById(deviceManagerId)!!

    every { user.canUpdateDeviceManager(deviceManagerId) } returns false

    assertThrows<AccessDeniedException> { store.update(initial) }
  }

  private fun insertDeviceManager(row: DeviceManagersRow = newRow()) {
    deviceManagersDao.insert(row)
  }

  private fun newRow(
      id: Any? = this.deviceManagerId,
      balenaId: Long = id?.toString()?.toLong() ?: 1L,
      balenaUuid: String = UUID.randomUUID().toString(),
      sensorKitId: String = "$id",
      userId: Any? = null,
      facilityId: Any? = null,
  ): DeviceManagersRow {
    return DeviceManagersRow(
        balenaModifiedTime = Instant.EPOCH,
        balenaId = BalenaDeviceId(balenaId),
        balenaUuid = balenaUuid,
        createdTime = Instant.EPOCH,
        deviceName = "Device $id",
        facilityId = facilityId?.toIdWrapper { FacilityId(it) },
        id = id?.toIdWrapper { DeviceManagerId(it) },
        isOnline = true,
        refreshedTime = Instant.EPOCH,
        sensorKitId = sensorKitId,
        userId = userId?.toIdWrapper { UserId(it) },
    )
  }
}
