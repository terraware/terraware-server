package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.DeviceTemplateCategory
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import java.time.Instant
import java.time.ZoneId
import org.jooq.Record

data class FacilityModel(
    val connectionState: FacilityConnectionState,
    val createdTime: Instant,
    val description: String?,
    val id: FacilityId,
    val modifiedTime: Instant,
    val name: String,
    val organizationId: OrganizationId,
    val type: FacilityType,
    val lastTimeseriesTime: Instant?,
    val maxIdleMinutes: Int,
    val timeZone: ZoneId? = null,
) {
  constructor(
      record: Record
  ) : this(
      record[FACILITIES.CONNECTION_STATE_ID]
          ?: throw IllegalArgumentException("Connection state is required"),
      record[FACILITIES.CREATED_TIME] ?: throw IllegalArgumentException("Created time is required"),
      record[FACILITIES.DESCRIPTION],
      record[FACILITIES.ID] ?: throw IllegalArgumentException("ID is required"),
      record[FACILITIES.MODIFIED_TIME]
          ?: throw IllegalArgumentException("Modified time is required"),
      record[FACILITIES.NAME] ?: throw IllegalArgumentException("Name is required"),
      record[FACILITIES.ORGANIZATION_ID]
          ?: throw IllegalArgumentException("Organization is required"),
      record[FACILITIES.TYPE_ID] ?: throw IllegalArgumentException("Type is required"),
      record[FACILITIES.LAST_TIMESERIES_TIME],
      record[FACILITIES.MAX_IDLE_MINUTES]
          ?: throw IllegalArgumentException("Max idle minutes is required"),
      record[FACILITIES.TIME_ZONE],
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
      connectionStateId ?: throw IllegalArgumentException("Connection state is required"),
      createdTime ?: throw IllegalArgumentException("Created time is required"),
      description,
      id ?: throw IllegalArgumentException("ID is required"),
      modifiedTime ?: throw IllegalArgumentException("Modified time is required"),
      name ?: throw IllegalArgumentException("Name is required"),
      organizationId ?: throw IllegalArgumentException("Organization is required"),
      typeId ?: throw IllegalArgumentException("Type is required"),
      lastTimeseriesTime,
      maxIdleMinutes ?: throw IllegalArgumentException("Max idle minutes is required"),
      timeZone,
  )
}
