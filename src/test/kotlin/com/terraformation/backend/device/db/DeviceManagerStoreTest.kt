package com.terraformation.backend.device.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.BalenaDeviceId
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.DeviceManagersRow
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class DeviceManagerStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock: Clock = mockk()
  private val store: DeviceManagerStore by lazy {
    DeviceManagerStore(clock, deviceManagersDao, dslContext)
  }

  private val deviceManagerId = DeviceManagerId(1)

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
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
  fun `fetchOneById returns null if user has no read permission`() {
    insertDeviceManager()

    every { user.canReadDeviceManager(deviceManagerId) } returns false

    assertNull(store.fetchOneById(deviceManagerId))
  }

  @Test
  fun `fetchOneById returns null if the device manager does not exist`() {
    assertNull(store.fetchOneById(deviceManagerId))
  }

  @Test
  fun `fetchOneBySensorKitId returns row for device manager`() {
    val sensorKitId = "xyzzy"

    insertDeviceManager(newRow(sensorKitId = sensorKitId))

    val expected = deviceManagersDao.fetchOneById(deviceManagerId)!!
    val actual = store.fetchOneBySensorKitId(sensorKitId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchOneBySensorKitId returns null if user has no read permission`() {
    val sensorKitId = "xyzzy"

    insertDeviceManager(newRow(sensorKitId = sensorKitId))

    every { user.canReadDeviceManager(deviceManagerId) } returns false

    assertNull(store.fetchOneBySensorKitId(sensorKitId))
  }

  @Test
  fun `fetchOneBySensorKitId returns null if no device manager has the short code`() {
    insertDeviceManager()

    assertNull(store.fetchOneBySensorKitId("nonexistentSensorKitId"))
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
    every { user.canUpdateFacility(facilityId) } returns false

    assertThrows<AccessDeniedException> { store.insert(newRow(id = null, facilityId = facilityId)) }
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
