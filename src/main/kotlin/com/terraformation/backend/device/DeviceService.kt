package com.terraformation.backend.device

import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class DeviceService(
    private val automationStore: AutomationStore,
    private val deviceStore: DeviceStore,
    private val dslContext: DSLContext,
) {
  private val log = perClassLogger()

  fun create(row: DevicesRow): DeviceId {
    return dslContext.transactionResult { _ ->
      val deviceId = deviceStore.create(row)
      updateAutomations(deviceId, row)

      deviceId
    }
  }

  fun update(row: DevicesRow) {
    val deviceId = row.id ?: throw IllegalArgumentException("No device ID specified")
    val existingRow = deviceStore.fetchOneById(deviceId) ?: throw DeviceNotFoundException(deviceId)

    dslContext.transaction { _ ->
      deviceStore.update(row)

      if (row.name != existingRow.name ||
          row.deviceType != existingRow.deviceType ||
          row.make != existingRow.make ||
          row.model != existingRow.model) {
        updateAutomations(deviceId, row.copy(facilityId = existingRow.facilityId))
      }
    }
  }

  private fun updateAutomations(deviceId: DeviceId, row: DevicesRow) {
    val facilityId = row.facilityId ?: throw IllegalArgumentException("No facility ID specified")
    val existingAutomations = automationStore.fetchByDeviceId(deviceId)
    val desiredAutomations = automationsForDevice(deviceId, row)

    existingAutomations.forEach { existingAutomation ->
      if (existingAutomation.type == AutomationModel.SENSOR_BOUNDS_TYPE) {
        log.info("Deleting automation ${existingAutomation.name} for device $deviceId")
        automationStore.delete(existingAutomation.id)
      } else {
        log.debug(
            "Keeping automation ${existingAutomation.name} for device $deviceId because it " +
                "is of type ${existingAutomation.type}")
      }
    }

    desiredAutomations.forEach { (name, configuration) ->
      log.info("Creating automation $name for device $deviceId")
      automationStore.create(facilityId, name, null, configuration)
    }
  }

  private fun automationsForDevice(
      deviceId: DeviceId,
      row: DevicesRow
  ): Map<String, Map<String, Any?>> {
    val name = row.name ?: throw IllegalArgumentException("No device name specified")

    return if (row.deviceType == "sensor" && row.make == "OmniSense" && row.model == "S-11") {
      automationsForTemperatureHumiditySensor(deviceId, name)
    } else if (row.deviceType == "BMU") {
      automationsForBMU(deviceId, name)
    } else {
      emptyMap()
    }
  }

  private fun automationsForBMU(deviceId: DeviceId, name: String): Map<String, Map<String, Any?>> {
    return mapOf(
        "$name 25% charge" to sensorBounds(deviceId, "relative_state_of_charge", 25.1, null),
        "$name 10% charge" to sensorBounds(deviceId, "relative_state_of_charge", 10.1, null),
        "$name 0% charge" to sensorBounds(deviceId, "relative_state_of_charge", 0.1, null),
    )
  }

  private fun automationsForTemperatureHumiditySensor(
      deviceId: DeviceId,
      name: String
  ): Map<String, Map<String, Any?>> {
    return when {
      name.startsWith("Ambient ") ->
          mapOf(
              "$name humidity" to sensorBounds(deviceId, "humidity", 34.0, 40.0),
              "$name temperature" to sensorBounds(deviceId, "temperature", 21.0, 25.0),
          )
      name.startsWith("Dry Cabinet ") ->
          mapOf(
              "$name humidity" to sensorBounds(deviceId, "humidity", 27.0, 33.0),
              "$name temperature" to sensorBounds(deviceId, "temperature", 21.0, 25.0),
          )
      name.startsWith("Freezer ") ->
          mapOf(
              "$name temperature" to sensorBounds(deviceId, "temperature", -25.0, -15.0),
          )
      name.startsWith("Fridge ") ->
          mapOf(
              "$name temperature" to sensorBounds(deviceId, "temperature", 0.0, 10.0),
          )
      else -> emptyMap()
    }
  }

  private fun sensorBounds(
      deviceId: DeviceId,
      timeseriesName: String,
      lowerBound: Double?,
      upperBound: Double?,
  ): Map<String, Any?> {
    return with(AutomationModel) {
      listOfNotNull(
              TYPE_KEY to SENSOR_BOUNDS_TYPE,
              DEVICE_ID_KEY to deviceId,
              TIMESERIES_NAME_KEY to timeseriesName,
              lowerBound?.let { LOWER_THRESHOLD_KEY to it },
              upperBound?.let { UPPER_THRESHOLD_KEY to it },
          )
          .toMap()
    }
  }
}
