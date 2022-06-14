package com.terraformation.backend.device

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.device.event.AutomationTriggeredEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.mockUser
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException

internal class AutomationServiceTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val automationStore: AutomationStore = mockk()
  private val eventPublisher: ApplicationEventPublisher = mockk()
  private val service = AutomationService(automationStore, eventPublisher)

  private val automationId = AutomationId(1)

  private val eventSlot = CapturingSlot<AutomationTriggeredEvent>()

  @BeforeEach
  fun setUp() {
    every { eventPublisher.publishEvent(capture(eventSlot)) } just Runs
    every { user.canReadAutomation(any()) } returns true
    every { user.canTriggerAutomation(any()) } returns true
  }

  @Test
  fun `trigger publishes event for sensor bounds alert`() {
    val timeseriesValue = 1.23

    every { automationStore.fetchOneById(automationId) } returns
        automationModel(type = AutomationModel.SENSOR_BOUNDS_TYPE)

    service.trigger(automationId, timeseriesValue, null)

    val expected = SensorBoundsAlertTriggeredEvent(automationId, timeseriesValue)

    assertEquals(expected, eventSlot.captured)
  }

  @Test
  fun `trigger publishes generic event if automation type is unrecognized`() {
    every { automationStore.fetchOneById(automationId) } returns
        automationModel(type = "bogus type")

    service.trigger(automationId, 1.23, "message")

    val expected = UnknownAutomationTriggeredEvent(automationId, "bogus type", "message")

    assertEquals(expected, eventSlot.captured)
  }

  @Test
  fun `trigger throws exception if no permission to trigger automation`() {
    every { user.canTriggerAutomation(automationId) } returns false

    assertThrows<AccessDeniedException> { service.trigger(automationId, 1.0, null) }
  }

  private fun automationModel(type: String?): AutomationModel =
      AutomationModel(
          automationId,
          FacilityId(1),
          "name",
          "description",
          Instant.EPOCH,
          Instant.EPOCH,
          mapOf(AutomationModel.TYPE_KEY to type))
}
