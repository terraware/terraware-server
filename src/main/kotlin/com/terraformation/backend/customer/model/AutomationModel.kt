package com.terraformation.backend.customer.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.pojos.AutomationsRow
import java.time.Instant

data class AutomationModel(
    val createdTime: Instant,
    val description: String?,
    val deviceId: DeviceId?,
    val facilityId: FacilityId,
    val id: AutomationId,
    val lowerThreshold: Double?,
    val modifiedTime: Instant,
    val name: String,
    val settings: Map<String, Any?>?,
    val timeseriesName: String?,
    val type: String,
    val upperThreshold: Double?,
    val verbosity: Int,
) {
  constructor(
      row: AutomationsRow,
      objectMapper: ObjectMapper
  ) : this(
      createdTime = row.createdTime ?: throw IllegalArgumentException("createdTime is required"),
      description = row.description,
      deviceId = row.deviceId,
      facilityId = row.facilityId ?: throw IllegalArgumentException("facilityId is required"),
      id = row.id ?: throw IllegalArgumentException("id is required"),
      lowerThreshold = row.lowerThreshold,
      modifiedTime = row.modifiedTime ?: throw IllegalArgumentException("modifiedTime is required"),
      name = row.name ?: throw IllegalArgumentException("name is required"),
      settings = row.settings?.data()?.let { objectMapper.readValue(it) },
      timeseriesName = row.timeseriesName,
      type = row.type ?: throw IllegalArgumentException("type is required"),
      upperThreshold = row.upperThreshold,
      verbosity = row.verbosity ?: throw IllegalArgumentException("verbosity is required"),
  )

  companion object {
    // Automation types recognized by the device manager (not a complete list; just the ones the
    // server needs to know about).

    const val SENSOR_BOUNDS_TYPE = "SensorBoundsAlert"
  }
}
