package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

internal class AutomationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val objectMapper = jacksonObjectMapper()
  private val store: AutomationStore by lazy {
    AutomationStore(automationsDao, clock, dslContext, objectMapper, ParentStore(dslContext))
  }

  @BeforeEach
  fun setUp() {
    every { user.canListAutomations(any()) } returns true
    every { user.canReadDevice(any()) } returns true

    insertOrganization()
    insertFacility()
  }

  @Test
  fun `fetchByDeviceId filters by device ID`() {
    val deviceId = insertDevice()
    val automationId = insertAutomation()
    insertDevice()
    insertAutomation()

    val expectedRow = automationsDao.fetchOneById(automationId)!!
    val expected = listOf(AutomationModel(expectedRow, objectMapper))
    val actual = store.fetchByDeviceId(deviceId)

    assertEquals(expected, actual)
  }
}
