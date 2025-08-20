package com.terraformation.backend.device.balena

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BalenaPollerTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val balenaClient: BalenaClient = mockk()
  private val clock = TestClock()

  private val poller: BalenaPoller by lazy {
    BalenaPoller(
        balenaClient,
        clock,
        DeviceManagerStore(clock, deviceManagersDao, dslContext),
        dslContext,
        SystemUser(usersDao),
    )
  }

  @BeforeEach
  fun setUp() {
    every { balenaClient.listModifiedDevices(any()) } returns emptyList()

    insertOrganization()
    insertFacility()
  }

  @Test
  fun `scans for all devices on first run`() {
    poller.updateBalenaDevices()

    verify { balenaClient.listModifiedDevices(Instant.EPOCH) }
  }

  @Test
  fun `scans since most recent modified timestamp from Balena servers`() {
    insertDeviceManager(
        balenaModifiedTime = Instant.ofEpochSecond(1000),
        refreshedTime = Instant.ofEpochSecond(5000),
    )
    insertDeviceManager(
        balenaModifiedTime = Instant.ofEpochSecond(3000),
        refreshedTime = Instant.ofEpochSecond(6000),
    )
    insertDeviceManager(
        balenaModifiedTime = Instant.ofEpochSecond(2000),
        refreshedTime = Instant.ofEpochSecond(7000),
    )

    poller.updateBalenaDevices()

    verify { balenaClient.listModifiedDevices(Instant.ofEpochSecond(3000)) }
  }

  @Test
  fun `inserts new device managers for unrecognized balena IDs`() {
    val device = balenaDevice()
    val balenaId = device.id

    every { balenaClient.listModifiedDevices(any()) } returns listOf(device)
    every { balenaClient.getSensorKitIdForBalenaId(balenaId) } returns "$balenaId"

    poller.updateBalenaDevices()

    val expected =
        listOf(
            DeviceManagersRow(
                balenaModifiedTime = Instant.EPOCH,
                balenaId = balenaId,
                balenaUuid = "uuid-$balenaId",
                createdTime = clock.instant(),
                deviceName = "Device $balenaId",
                isOnline = false,
                refreshedTime = Instant.EPOCH,
                sensorKitId = "$balenaId",
            )
        )
    val actual = deviceManagersDao.findAll().onEach { it.id = null }

    assertEquals(expected, actual)
  }

  @Test
  fun `skips new devices that do not have short codes`() {
    val device = balenaDevice()

    every { balenaClient.listModifiedDevices(any()) } returns listOf(device)
    every { balenaClient.getSensorKitIdForBalenaId(device.id) } returns null

    poller.updateBalenaDevices()

    val expected = emptyList<DeviceManagersRow>()
    val actual = deviceManagersDao.findAll()

    assertEquals(expected, actual)
  }

  @Test
  fun `updates existing devices based on Balena device ID`() {
    val existingRow = insertDeviceManager(facilityId = inserted.facilityId)
    val device =
        balenaDevice(
            id = existingRow.balenaId!!,
            isOnline = true,
            lastConnectivityEvent = Instant.ofEpochSecond(50),
            modifiedAt = Instant.ofEpochSecond(100),
            overallProgress = 30,
        )

    clock.instant = Instant.ofEpochSecond(200)
    every { balenaClient.listModifiedDevices(any()) } returns listOf(device)

    poller.updateBalenaDevices()

    val expected =
        listOf(
            existingRow.copy(
                balenaModifiedTime = device.modifiedAt,
                isOnline = device.isOnline,
                lastConnectivityEvent = device.lastConnectivityEvent,
                refreshedTime = clock.instant(),
                updateProgress = device.overallProgress,
            )
        )
    val actual = deviceManagersDao.findAll()

    assertEquals(expected, actual)
  }

  private fun balenaDevice(
      id: BalenaDeviceId = BalenaDeviceId(nextBalenaId.getAndIncrement()),
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
        deviceName = deviceName,
        id = id,
        isActive = isActive,
        isOnline = isOnline,
        lastConnectivityEvent = lastConnectivityEvent,
        modifiedAt = modifiedAt,
        overallProgress = overallProgress,
        provisioningState = provisioningState,
        status = status,
        uuid = uuid,
    )
  }
}
