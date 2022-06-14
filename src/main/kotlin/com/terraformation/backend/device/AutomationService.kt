package com.terraformation.backend.device

import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.springframework.context.ApplicationEventPublisher

@ManagedBean
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
    val automationType =
        automation.type
            ?: run {
              log.warn("Ignoring trigger for automation $automationId with no type")
              return
            }

    val event =
        when (automationType) {
          AutomationModel.SENSOR_BOUNDS_TYPE -> {
            if (timeseriesValue == null) {
              throw IllegalArgumentException("Sensor bounds alert must include a timeseries value")
            }
            SensorBoundsAlertTriggeredEvent(automationId, timeseriesValue)
          }
          else -> UnknownAutomationTriggeredEvent(automationId, automationType, message)
        }

    log.info(
        "Automation $automationId ($automationType ${automation.name}) triggered with " +
            "value $timeseriesValue")
    eventPublisher.publishEvent(event)
  }
}
