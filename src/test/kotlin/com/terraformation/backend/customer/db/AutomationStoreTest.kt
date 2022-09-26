package com.terraformation.backend.customer.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AutomationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock: Clock = mockk()
  private val objectMapper = jacksonObjectMapper()
  private val store: AutomationStore by lazy {
    AutomationStore(automationsDao, clock, dslContext, objectMapper, ParentStore(dslContext))
  }

  private val deviceId = DeviceId(1000)

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { user.canListAutomations(any()) } returns true
    every { user.canReadDevice(any()) } returns true

    insertSiteData()
    insertDevice(deviceId)
  }

  @Test
  fun `fetchByDeviceId filters by device ID`() {
    val otherDeviceId = DeviceId(1001)

    insertDevice(otherDeviceId)

    insertAutomation(1, deviceId = deviceId, objectMapper = objectMapper)
    insertAutomation(2, deviceId = otherDeviceId, objectMapper = objectMapper)

    val expectedRow = automationsDao.fetchOneById(AutomationId(1))!!
    val expected = listOf(AutomationModel(expectedRow, objectMapper))
    val actual = store.fetchByDeviceId(deviceId)

    assertEquals(expected, actual)
  }
}
