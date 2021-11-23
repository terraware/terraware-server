package com.terraformation.backend.customer.model

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.tables.references.FACILITIES
import org.jooq.Record

data class FacilityModel(
    val id: FacilityId,
    val siteId: SiteId,
    val name: String,
    val type: FacilityType
) {
  constructor(
      record: Record
  ) : this(
      record[FACILITIES.ID] ?: throw IllegalArgumentException("ID is required"),
      record[FACILITIES.SITE_ID] ?: throw IllegalArgumentException("Site is required"),
      record[FACILITIES.NAME] ?: throw IllegalArgumentException("Name is required"),
      record[FACILITIES.TYPE_ID] ?: throw IllegalArgumentException("Type is required"))
}

fun FacilitiesRow.toModel(): FacilityModel {
  return FacilityModel(
      id ?: throw IllegalArgumentException("ID is required"),
      siteId ?: throw IllegalArgumentException("Site is required"),
      name ?: throw IllegalArgumentException("Name is required"),
      typeId ?: throw IllegalArgumentException("Type is required"))
}
