package com.terraformation.backend.device.event

import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.db.default_schema.AutomationId

interface AutomationTriggeredEvent {
  val automationId: AutomationId
  val type: String
  val message: String?
}

/**
 * Published when the device manager reports that an automation of type `SensorBoundsAlert` has been
 * triggered.
 */
data class SensorBoundsAlertTriggeredEvent(
    override val automationId: AutomationId,
    val value: Double,
) : AutomationTriggeredEvent {
  override val message: String?
    get() = null

  override val type: String
    get() = AutomationModel.SENSOR_BOUNDS_TYPE
}

/**
 * Published when the device manager reports that an automation has been triggered but it is of an
 * unrecognized type.
 */
data class UnknownAutomationTriggeredEvent(
    override val automationId: AutomationId,
    override val type: String,
    override val message: String?,
) : AutomationTriggeredEvent
