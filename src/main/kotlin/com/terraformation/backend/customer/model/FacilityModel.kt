package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.DeviceTemplateCategory
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.jooq.Record

/** Maximum device manager idle time, in minutes, to assign to new facilities by default. */
const val DEFAULT_MAX_IDLE_MINUTES = 30

data class NewFacilityModel(
    val buildCompletedDate: LocalDate? = null,
    val buildStartedDate: LocalDate? = null,
    val capacity: Int? = null,
    val description: String? = null,
    val name: String,
    val maxIdleMinutes: Int = DEFAULT_MAX_IDLE_MINUTES,
    val operationStartedDate: LocalDate? = null,
    val organizationId: OrganizationId,
    val subLocationNames: Set<String>? = null,
    val timeZone: ZoneId? = null,
    val type: FacilityType,
)

data class FacilityModel(
    val buildCompletedDate: LocalDate? = null,
    val buildStartedDate: LocalDate? = null,
    val capacity: Int? = null,
    val connectionState: FacilityConnectionState,
    val createdTime: Instant,
    val description: String?,
    val facilityNumber: Int,
    val id: FacilityId,
    val lastNotificationDate: LocalDate? = null,
    val lastTimeseriesTime: Instant?,
    val maxIdleMinutes: Int,
    val modifiedTime: Instant,
    val name: String,
    val nextNotificationTime: Instant,
    val operationStartedDate: LocalDate? = null,
    val organizationId: OrganizationId,
    val timeZone: ZoneId? = null,
    val type: FacilityType,
) {
  constructor(
      record: Record
  ) : this(
      buildCompletedDate = record[FACILITIES.BUILD_COMPLETED_DATE],
      buildStartedDate = record[FACILITIES.BUILD_STARTED_DATE],
      capacity = record[FACILITIES.CAPACITY],
      connectionState = record[FACILITIES.CONNECTION_STATE_ID]
              ?: throw IllegalArgumentException("Connection state is required"),
      createdTime = record[FACILITIES.CREATED_TIME]
              ?: throw IllegalArgumentException("Created time is required"),
      description = record[FACILITIES.DESCRIPTION],
      facilityNumber = record[FACILITIES.FACILITY_NUMBER]
              ?: throw IllegalArgumentException("Facility number is required"),
      id = record[FACILITIES.ID] ?: throw IllegalArgumentException("ID is required"),
      lastNotificationDate = record[FACILITIES.LAST_NOTIFICATION_DATE],
      lastTimeseriesTime = record[FACILITIES.LAST_TIMESERIES_TIME],
      maxIdleMinutes = record[FACILITIES.MAX_IDLE_MINUTES]
              ?: throw IllegalArgumentException("Max idle minutes is required"),
      modifiedTime = record[FACILITIES.MODIFIED_TIME]
              ?: throw IllegalArgumentException("Modified time is required"),
      name = record[FACILITIES.NAME] ?: throw IllegalArgumentException("Name is required"),
      nextNotificationTime = record[FACILITIES.NEXT_NOTIFICATION_TIME]
              ?: throw IllegalArgumentException("Next notification time is required"),
      operationStartedDate = record[FACILITIES.OPERATION_STARTED_DATE],
      organizationId = record[FACILITIES.ORGANIZATION_ID]
              ?: throw IllegalArgumentException("Organization is required"),
      timeZone = record[FACILITIES.TIME_ZONE],
      type = record[FACILITIES.TYPE_ID] ?: throw IllegalArgumentException("Type is required"),
  )

  /**
   * The device template category that holds the list of default devices to create when a sensor kit
   * is connected to this facility. Null if the facility doesn't need default devices. Currently,
   * this is based on facility type.
   */
  val defaultDeviceTemplateCategory: DeviceTemplateCategory?
    get() =
        when (type) {
          FacilityType.SeedBank -> DeviceTemplateCategory.SeedBankDefault
          FacilityType.Desalination,
          FacilityType.Nursery,
          FacilityType.ReverseOsmosis -> null
        }
}

fun FacilitiesRow.toModel(): FacilityModel {
  return FacilityModel(
      buildCompletedDate = buildCompletedDate,
      buildStartedDate = buildStartedDate,
      capacity = capacity,
      connectionState = connectionStateId
              ?: throw IllegalArgumentException("Connection state is required"),
      createdTime = createdTime ?: throw IllegalArgumentException("Created time is required"),
      description = description,
      facilityNumber = facilityNumber
              ?: throw IllegalArgumentException("Facility number is required"),
      id = id ?: throw IllegalArgumentException("ID is required"),
      lastNotificationDate = lastNotificationDate,
      lastTimeseriesTime = lastTimeseriesTime,
      maxIdleMinutes = maxIdleMinutes
              ?: throw IllegalArgumentException("Max idle minutes is required"),
      modifiedTime = modifiedTime ?: throw IllegalArgumentException("Modified time is required"),
      name = name ?: throw IllegalArgumentException("Name is required"),
      nextNotificationTime = nextNotificationTime
              ?: throw IllegalArgumentException("Next notification time is required"),
      operationStartedDate = operationStartedDate,
      organizationId = organizationId ?: throw IllegalArgumentException("Organization is required"),
      timeZone = timeZone,
      type = typeId ?: throw IllegalArgumentException("Type is required"),
  )
}
