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
    val location: String?,
    val disabledTime: Instant? = null,
    val projects: List<ProjectModel>? = null,
) {
  constructor(
      record: Record,
      projectsMultiset: Field<List<ProjectModel>?>
  ) : this(
      record[ORGANIZATIONS.ID] ?: throw IllegalArgumentException("ID is required"),
      record[ORGANIZATIONS.NAME] ?: throw IllegalArgumentException("Name is required"),
      record[ORGANIZATIONS.LOCATION],
      record[ORGANIZATIONS.DISABLED_TIME],
      record[projectsMultiset],
  )
}

fun OrganizationsRow.toModel(): OrganizationModel =
    OrganizationModel(
        id ?: throw IllegalArgumentException("ID is required"),
        name ?: throw IllegalArgumentException("Name is required"),
        location,
        disabledTime)
