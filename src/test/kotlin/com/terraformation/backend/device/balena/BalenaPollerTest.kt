package com.terraformation.backend.device.balena

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BalenaPollerTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val balenaClient: BalenaClient = mockk()
  private val clock: Clock = mockk()

  private val poller: BalenaPoller by lazy {
    BalenaPoller(
        balenaClient,
        clock,
        DeviceManagerStore(clock, deviceManagersDao, dslContext),
        dslContext,
        SystemUser(usersDao))
  }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { balenaClient.listModifiedDevices(any()) } returns emptyList()

    insertSiteData()
  }

  @Test
  fun `scans for all devices on first run`() {
    poller.updateBalenaDevices()

    verify { balenaClient.listModifiedDevices(Instant.EPOCH) }
  }

  @Test
  fun `scans since most recent modified timestamp from Balena servers`() {
    insertDeviceManager(
        1,
        balenaModifiedTime = Instant.ofEpochSecond(1000),
        refreshedTime = Instant.ofEpochSecond(5000))
    insertDeviceManager(
        2,
        balenaModifiedTime = Instant.ofEpochSecond(3000),
        refreshedTime = Instant.ofEpochSecond(6000))
    insertDeviceManager(
        3,
        balenaModifiedTime = Instant.ofEpochSecond(2000),
        refreshedTime = Instant.ofEpochSecond(7000))

    poller.updateBalenaDevices()

    verify { balenaClient.listModifiedDevices(Instant.ofEpochSecond(3000)) }
  }

  @Test
  fun `inserts new device managers for unrecognized balena IDs`() {
    val device = balenaDevice(1)

    every { balenaClient.listModifiedDevices(any()) } returns listOf(device)
    every { balenaClient.getSensorKitIdForBalenaId(device.id) } returns "${device.id}"

    poller.updateBalenaDevices()

    val expected = listOf(deviceManagersRow(balenaId = device.id))
    val actual = deviceManagersDao.findAll().onEach { it.id = null }

    assertEquals(expected, actual)
  }

  @Test
  fun `skips new devices that do not have short codes`() {
    val device = balenaDevice(1)

    every { balenaClient.listModifiedDevices(any()) } returns listOf(device)
    every { balenaClient.getSensorKitIdForBalenaId(device.id) } returns null

    poller.updateBalenaDevices()

    val expected = emptyList<DeviceManagersRow>()
    val actual = deviceManagersDao.findAll()

    assertEquals(expected, actual)
  }

  @Test
  fun `updates existing devices based on Balena device ID`() {
    val device =
        balenaDevice(
            123,
            isOnline = true,
            lastConnectivityEvent = Instant.ofEpochSecond(50),
            modifiedAt = Instant.ofEpochSecond(100),
            overallProgress = 30,
        )

    val existingRow = insertDeviceManager(456, balenaId = device.id, facilityId = 100)

    every { clock.instant() } returns Instant.ofEpochSecond(200)
    every { balenaClient.listModifiedDevices(any()) } returns listOf(device)

    poller.updateBalenaDevices()

    val expected =
        listOf(
            existingRow.copy(
                balenaModifiedTime = device.modifiedAt,
                isOnline = device.isOnline,
                lastConnectivityEvent = device.lastConnectivityEvent,
                refreshedTime = clock.instant(),
                updateProgress = device.overallProgress))
    val actual = deviceManagersDao.findAll()

    assertEquals(expected, actual)
  }

  private fun balenaDevice(
      id: Any,
      deviceName: String = "Device $id",
      isActive: Boolean = false,
      isOnline: Boolean = false,
      lastConnectivityEvent: Instant? = null,
      modifiedAt: Instant = clock.instant(),
      overallProgress: Int? = null,
      provisioningState: String? = null,
      status: String? = null,
      uuid: String = "uuid-$id",
  ): BalenaDevice {
    return BalenaDevice(
        deviceName,
        id.toIdWrapper { BalenaDeviceId(it) },
        isActive,
        isOnline,
        lastConnectivityEvent,
        modifiedAt,
        overallProgress,
        provisioningState,
        status,
        uuid)
  }

  private fun deviceManagersRow(
      id: Any? = null,
      balenaId: Any = "$id".toLong(),
      balenaUuid: String = "uuid-$balenaId",
      balenaModifiedTime: Instant = Instant.EPOCH,
      facilityId: Any? = null,
      isOnline: Boolean = false,
      sensorKitId: String = "$balenaId",
      refreshedTime: Instant = Instant.EPOCH,
  ): DeviceManagersRow {
    return DeviceManagersRow(
        balenaModifiedTime = balenaModifiedTime,
        balenaId = balenaId.toIdWrapper { BalenaDeviceId(it) },
        balenaUuid = balenaUuid,
        createdTime = clock.instant(),
        deviceName = "Device $balenaId",
        facilityId = facilityId?.toIdWrapper { FacilityId(it) },
        id = id?.toIdWrapper { DeviceManagerId(it) },
        isOnline = isOnline,
        refreshedTime = refreshedTime,
        sensorKitId = sensorKitId,
        userId = if (facilityId != null) user.userId else null,
    )
  }

  private fun insertDeviceManager(
      id: Any,
      balenaId: Any = "$id".toLong(),
      balenaUuid: String = "uuid-$balenaId",
      balenaModifiedTime: Instant = Instant.EPOCH,
      facilityId: Any? = null,
      isOnline: Boolean = false,
      sensorKitId: String = "$balenaId",
      refreshedTime: Instant = Instant.EPOCH,
  ): DeviceManagersRow {
    val row =
        deviceManagersRow(
            id,
            balenaId,
            balenaUuid,
            balenaModifiedTime,
            facilityId,
            isOnline,
            sensorKitId,
            refreshedTime)

    deviceManagersDao.insert(row)
    return row
  }
}
