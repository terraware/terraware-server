package com.terraformation.backend.device

import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.springframework.context.ApplicationEventPublisher

@Named
class AutomationService(
    private val automationStore: AutomationStore,
    private val eventPublisher: ApplicationEventPublisher,
) {
  private val log = perClassLogger()

  /**
   * Publishes an application event of the correct type to indicate that an automation has been
   * triggered.
   */
  fun trigger(automationId: AutomationId, timeseriesValue: Double?, message: String?) {
    requirePermissions { triggerAutomation(automationId) }

    val automation = automationStore.fetchOneById(automationId)

    val event =
        when (automation.type) {
          AutomationModel.SENSOR_BOUNDS_TYPE -> {
            if (timeseriesValue == null) {
              throw IllegalArgumentException("Sensor bounds alert must include a timeseries value")
            }
            SensorBoundsAlertTriggeredEvent(automationId, timeseriesValue)
          }
          else -> UnknownAutomationTriggeredEvent(automationId, automation.type, message)
        }

    log.info(
        "Automation $automationId (${automation.type} ${automation.name}) triggered with " +
            "value $timeseriesValue"
    )
    eventPublisher.publishEvent(event)
  }
}
