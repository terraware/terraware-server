package com.terraformation.backend.customer.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.AutomationId
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
}
