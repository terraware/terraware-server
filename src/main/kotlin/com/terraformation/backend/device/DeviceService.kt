package com.terraformation.backend.device

import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.Duration
import java.time.Instant
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

@Named
class DeviceService(
    private val automationStore: AutomationStore,
    private val deviceStore: DeviceStore,
    private val deviceTemplatesDao: DeviceTemplatesDao,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilityStore: FacilityStore,
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
    val existingRow = deviceStore.fetchOneById(deviceId)

    dslContext.transaction { _ ->
      deviceStore.update(row)

      if (
          row.name != existingRow.name ||
              row.deviceType != existingRow.deviceType ||
              row.make != existingRow.make ||
              row.model != existingRow.model
      ) {
        updateAutomations(deviceId, row.copy(facilityId = existingRow.facilityId))
      }
    }
  }

  fun markUnresponsive(
      deviceId: DeviceId,
      lastRespondedTime: Instant?,
      expectedInterval: Duration?,
  ) {
    requirePermissions { updateDevice(deviceId) }

    eventPublisher.publishEvent(
        DeviceUnresponsiveEvent(deviceId, lastRespondedTime, expectedInterval)
    )
  }

  /**
   * Creates a default set of devices based on the facility type. Facility types that need default
   * devices have corresponding device template categories, and this method makes a device for each
   * template in the appropriate category.
   *
   * This is used to tell the device manager about devices that will always be present at a certain
   * type of facility, but that it can't detect automatically.
   */
  fun createDefaultDevices(facilityId: FacilityId) {
    val category = facilityStore.fetchOneById(facilityId).defaultDeviceTemplateCategory

    if (category != null) {
      dslContext.transaction { _ ->
        deviceTemplatesDao
            .fetchByCategoryId(category)
            .map { template ->
              DevicesRow(
                  facilityId = facilityId,
                  name = template.name,
                  deviceType = template.deviceType,
                  make = template.make,
                  model = template.model,
                  protocol = template.protocol,
                  address = template.address,
                  port = template.port,
                  enabled = true,
                  settings = template.settings,
                  verbosity = template.verbosity,
              )
            }
            .forEach { create(it) }
      }
    }
  }

  private fun updateAutomations(deviceId: DeviceId, row: DevicesRow) {
    val facilityId = row.facilityId ?: throw IllegalArgumentException("No facility ID specified")
    val existingAutomations = automationStore.fetchByDeviceId(deviceId)

    existingAutomations.forEach { existingAutomation ->
      if (existingAutomation.type == AutomationModel.SENSOR_BOUNDS_TYPE) {
        log.info("Deleting automation ${existingAutomation.name} for device $deviceId")
        automationStore.delete(existingAutomation.id)
      } else {
        log.debug(
            "Keeping automation ${existingAutomation.name} for device $deviceId because it " +
                "is of type ${existingAutomation.type}"
        )
      }
    }

    createAutomations(facilityId, deviceId, row)
  }

  private fun createAutomations(facilityId: FacilityId, deviceId: DeviceId, row: DevicesRow) {
    val name = row.name ?: throw IllegalArgumentException("No device name specified")

    if (row.deviceType == "sensor" && row.make == "OmniSense" && row.model == "S-11") {
      createTemperatureHumiditySensorAutomations(facilityId, deviceId, name)
    } else if (row.deviceType == "BMU") {
      createBmuAutomations(facilityId, deviceId, name)
    }
  }

  private fun createBmuAutomations(facilityId: FacilityId, deviceId: DeviceId, name: String) {
    createBoundsAutomation(
        "$name 25% charge",
        facilityId,
        deviceId,
        "relative_state_of_charge",
        25.1,
        null,
    )
    createBoundsAutomation(
        "$name 10% charge",
        facilityId,
        deviceId,
        "relative_state_of_charge",
        10.1,
        null,
    )
    createBoundsAutomation(
        "$name 0% charge",
        facilityId,
        deviceId,
        "relative_state_of_charge",
        0.1,
        null,
    )
  }

  private fun createTemperatureHumiditySensorAutomations(
      facilityId: FacilityId,
      deviceId: DeviceId,
      name: String,
  ) {
    when {
      name.startsWith("Ambient ") -> {
        createBoundsAutomation("$name humidity", facilityId, deviceId, "humidity", 34.0, 40.0)
        createBoundsAutomation("$name temperature", facilityId, deviceId, "temperature", 21.0, 25.0)
      }
      name.startsWith("Dry Cabinet ") -> {
        createBoundsAutomation("$name humidity", facilityId, deviceId, "humidity", 27.0, 33.0)
        createBoundsAutomation("$name temperature", facilityId, deviceId, "temperature", 21.0, 25.0)
      }
      name.startsWith("Freezer ") -> {
        createBoundsAutomation(
            "$name temperature",
            facilityId,
            deviceId,
            "temperature",
            -25.0,
            -15.0,
        )
      }
      name.startsWith("Fridge ") -> {
        createBoundsAutomation("$name temperature", facilityId, deviceId, "temperature", 0.0, 10.0)
      }
    }
  }

  private fun createBoundsAutomation(
      name: String,
      facilityId: FacilityId,
      deviceId: DeviceId,
      timeseriesName: String,
      lowerThreshold: Double?,
      upperThreshold: Double?,
  ) {
    log.info("Creating automation $name for device $deviceId")

    automationStore.create(
        deviceId = deviceId,
        facilityId = facilityId,
        lowerThreshold = lowerThreshold,
        name = name,
        timeseriesName = timeseriesName,
        type = AutomationModel.SENSOR_BOUNDS_TYPE,
        upperThreshold = upperThreshold,
    )
  }
}
