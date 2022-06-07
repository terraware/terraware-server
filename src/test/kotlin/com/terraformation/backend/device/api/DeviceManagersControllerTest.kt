package com.terraformation.backend.device.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.pojos.DeviceManagersRow
import com.terraformation.backend.device.DeviceManagerService
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class DeviceManagersControllerTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private val deviceManagerService: DeviceManagerService = mockk()
  private val deviceManagerStore: DeviceManagerStore = mockk()
  private val deviceManagersController =
      DeviceManagersController(deviceManagerService, deviceManagerStore)

  @Test
  fun `throws exception when neither shortCode nor facility ID are specified`() {
    assertThrows<IllegalArgumentException> {
      deviceManagersController.getDeviceManagers(null, null)
    }
  }

  @Test
  fun `throws exception when both shortCode and facility ID are specified`() {
    assertThrows<IllegalArgumentException> {
      deviceManagersController.getDeviceManagers("123456", FacilityId(1))
    }
  }

  @Test
  fun `returns device manager for shortCode`() {
    val deviceManager = DeviceManagersRow(id = DeviceManagerId(1), shortCode = "123456")
    every { deviceManagerStore.fetchOneByShortCode("123456") } returns deviceManager
    val expected = GetDeviceManagersResponsePayload(listOf(DeviceManagerPayload(deviceManager)))
    assertEquals(expected, deviceManagersController.getDeviceManagers("123456", null))
  }

  @Test
  fun `returns device manager for facilityID`() {
    val deviceManager =
        DeviceManagersRow(id = DeviceManagerId(1), shortCode = "123456", facilityId = FacilityId(2))
    every { deviceManagerStore.fetchOneByFacilityId(FacilityId(2)) } returns deviceManager
    val expected = GetDeviceManagersResponsePayload(listOf(DeviceManagerPayload(deviceManager)))
    assertEquals(expected, deviceManagersController.getDeviceManagers(null, FacilityId(2)))
  }
}
