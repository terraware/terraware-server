package com.terraformation.backend.customer.model

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import java.time.Instant
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
    val projects: List<ProjectModel>? = null,
    val totalUsers: Int,
) {
  constructor(
      record: Record,
      projectsMultiset: Field<List<ProjectModel>?>,
      totalUsersSubquery: Field<Int>,
  ) : this(
      countryCode = record[ORGANIZATIONS.COUNTRY_CODE],
      countrySubdivisionCode = record[ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE],
      createdTime = record[ORGANIZATIONS.CREATED_TIME]
              ?: throw IllegalArgumentException("Created time is required"),
      description = record[ORGANIZATIONS.DESCRIPTION],
      disabledTime = record[ORGANIZATIONS.DISABLED_TIME],
      id = record[ORGANIZATIONS.ID] ?: throw IllegalArgumentException("ID is required"),
      name = record[ORGANIZATIONS.NAME] ?: throw IllegalArgumentException("Name is required"),
      projects = record[projectsMultiset],
      totalUsers = record[totalUsersSubquery],
  )
}

fun OrganizationsRow.toModel(totalUsers: Int): OrganizationModel =
    OrganizationModel(
        countryCode = countryCode,
        countrySubdivisionCode = countrySubdivisionCode,
        createdTime = createdTime ?: throw IllegalArgumentException("Created time is required"),
        description = description,
        disabledTime = disabledTime,
        id = id ?: throw IllegalArgumentException("ID is required"),
        name = name ?: throw IllegalArgumentException("Name is required"),
        totalUsers = totalUsers,
    )
