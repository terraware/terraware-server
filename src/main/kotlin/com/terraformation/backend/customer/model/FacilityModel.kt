package com.terraformation.backend.customer.model

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.pojos.FacilitiesRow

data class FacilityModel(
    val id: FacilityId,
    val siteId: SiteId,
    val name: String,
    val type: FacilityType
)

fun FacilitiesRow.toModel(): FacilityModel {
  return FacilityModel(
      id ?: throw IllegalArgumentException("ID is required"),
      siteId ?: throw IllegalArgumentException("Site is required"),
      name ?: throw IllegalArgumentException("Name is required"),
      typeId ?: throw IllegalArgumentException("Type is required"))
}
