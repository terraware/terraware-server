package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import java.time.Instant
import java.time.ZoneId
import org.jooq.Field
import org.jooq.Record

/** Represents an organization that has already been committed to the database. */
data class OrganizationModel(
    val id: OrganizationId,
    val name: String,
    val description: String? = null,
    val countryCode: String? = null,
    val countrySubdivisionCode: String? = null,
    val createdTime: Instant,
    val disabledTime: Instant? = null,
    val internalTags: Set<InternalTagId> = emptySet(),
    val facilities: List<FacilityModel>? = null,
    val totalUsers: Int,
    val timeZone: ZoneId? = null,
) {
  constructor(
      record: Record,
      facilitiesMultiset: Field<List<FacilityModel>>?,
      internalTagsMultiset: Field<List<InternalTagId>>,
      totalUsersSubquery: Field<Int>,
  ) : this(
      id = record[ORGANIZATIONS.ID] ?: throw IllegalArgumentException("ID is required"),
      name = record[ORGANIZATIONS.NAME] ?: throw IllegalArgumentException("Name is required"),
      description = record[ORGANIZATIONS.DESCRIPTION],
      countryCode = record[ORGANIZATIONS.COUNTRY_CODE],
      countrySubdivisionCode = record[ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE],
      createdTime = record[ORGANIZATIONS.CREATED_TIME]
              ?: throw IllegalArgumentException("Created time is required"),
      disabledTime = record[ORGANIZATIONS.DISABLED_TIME],
      facilities = record[facilitiesMultiset],
      internalTags = record[internalTagsMultiset]?.toSet() ?: emptySet(),
      totalUsers = record[totalUsersSubquery],
      timeZone = record[ORGANIZATIONS.TIME_ZONE],
  )
}

fun OrganizationsRow.toModel(totalUsers: Int): OrganizationModel =
    OrganizationModel(
        id = id ?: throw IllegalArgumentException("ID is required"),
        name = name ?: throw IllegalArgumentException("Name is required"),
        description = description,
        countryCode = countryCode,
        countrySubdivisionCode = countrySubdivisionCode,
        createdTime = createdTime ?: throw IllegalArgumentException("Created time is required"),
        disabledTime = disabledTime,
        totalUsers = totalUsers,
        timeZone = timeZone,
    )
