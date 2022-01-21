package com.terraformation.backend.customer.model

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.tables.references.FACILITIES
import java.time.Instant
import org.jooq.Record

data class FacilityModel(
    val createdTime: Instant,
    val id: FacilityId,
    val modifiedTime: Instant,
    val name: String,
    val siteId: SiteId,
    val type: FacilityType,
) {
  constructor(
      record: Record
  ) : this(
      record[FACILITIES.CREATED_TIME] ?: throw IllegalArgumentException("Created time is required"),
      record[FACILITIES.ID] ?: throw IllegalArgumentException("ID is required"),
      record[FACILITIES.MODIFIED_TIME]
          ?: throw IllegalArgumentException("Modified time is required"),
      record[FACILITIES.NAME] ?: throw IllegalArgumentException("Name is required"),
      record[FACILITIES.SITE_ID] ?: throw IllegalArgumentException("Site is required"),
      record[FACILITIES.TYPE_ID] ?: throw IllegalArgumentException("Type is required"))
}

fun FacilitiesRow.toModel(): FacilityModel {
  return FacilityModel(
      createdTime ?: throw IllegalArgumentException("Created time is required"),
      id ?: throw IllegalArgumentException("ID is required"),
      modifiedTime ?: throw IllegalArgumentException("Modified time is required"),
      name ?: throw IllegalArgumentException("Name is required"),
      siteId ?: throw IllegalArgumentException("Site is required"),
      typeId ?: throw IllegalArgumentException("Type is required"))
}
