package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.OrganizationType
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
    val organizationType: OrganizationType? = null,
    val organizationTypeDetails: String? = null,
    val totalUsers: Int,
    val timeZone: ZoneId? = null,
    val website: String? = null,
) {
  constructor(
      record: Record,
      facilitiesMultiset: Field<out List<FacilityModel>>? = null,
      internalTagsMultiset: Field<out List<InternalTagId>>? = null,
      totalUsersSubquery: Field<Int>? = null,
  ) : this(
      id = record[ORGANIZATIONS.ID] ?: throw IllegalArgumentException("ID is required"),
      name = record[ORGANIZATIONS.NAME] ?: throw IllegalArgumentException("Name is required"),
      description = record[ORGANIZATIONS.DESCRIPTION],
      countryCode = record[ORGANIZATIONS.COUNTRY_CODE],
      countrySubdivisionCode = record[ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE],
      createdTime =
          record[ORGANIZATIONS.CREATED_TIME]
              ?: throw IllegalArgumentException("Created time is required"),
      disabledTime = record[ORGANIZATIONS.DISABLED_TIME],
      facilities = facilitiesMultiset?.let { record[it] },
      internalTags = internalTagsMultiset?.let { record[it]?.toSet() } ?: emptySet(),
      organizationType = record[ORGANIZATIONS.ORGANIZATION_TYPE_ID],
      organizationTypeDetails = record[ORGANIZATIONS.ORGANIZATION_TYPE_DETAILS],
      totalUsers = totalUsersSubquery?.let { record[it] } ?: 0,
      timeZone = record[ORGANIZATIONS.TIME_ZONE],
      website = record[ORGANIZATIONS.WEBSITE],
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
        organizationType = organizationTypeId,
        organizationTypeDetails = organizationTypeDetails,
        totalUsers = totalUsers,
        timeZone = timeZone,
        website = website,
    )
