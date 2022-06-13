package com.terraformation.backend.customer.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.pojos.AutomationsRow
import java.time.Instant

data class AutomationModel(
    val id: AutomationId,
    val facilityId: FacilityId,
    val name: String,
    val description: String?,
    val createdTime: Instant,
    val modifiedTime: Instant,
    val configuration: Map<String, Any?>?
) {
  constructor(
      row: AutomationsRow,
      objectMapper: ObjectMapper
  ) : this(
      id = row.id ?: throw IllegalArgumentException("id is required"),
      facilityId = row.facilityId ?: throw IllegalArgumentException("facilityId is required"),
      name = row.name ?: throw IllegalArgumentException("name is required"),
      description = row.description,
      createdTime = row.createdTime ?: throw IllegalArgumentException("createdTime is required"),
      modifiedTime = row.modifiedTime ?: throw IllegalArgumentException("modifiedTime is required"),
      configuration = row.configuration?.data()?.let { objectMapper.readValue(it) })

  val deviceId: DeviceId?
    get() = configuration?.get(DEVICE_ID_KEY)?.toString()?.let { DeviceId(it) }
  val timeseriesName: String?
    get() = configuration?.get(TIMESERIES_NAME_KEY)?.toString()
  val type: String?
    get() = configuration?.get(TYPE_KEY)?.toString()

  companion object {
    // Configuration keys recognized by the device manager.

    const val DEVICE_ID_KEY = "monitorDeviceId"
    const val LOWER_THRESHOLD_KEY = "lowerThreshold"
    const val TIMESERIES_NAME_KEY = "monitorTimeseriesName"
    const val TYPE_KEY = "type"
    const val UPPER_THRESHOLD_KEY = "upperThreshold"

    // Automation types recognized by the device manager (not a complete list; just the ones the
    // server needs to know about).

    const val SENSOR_BOUNDS_TYPE = "SensorBoundsAlert"
  }
}
