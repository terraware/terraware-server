package com.terraformation.backend.customer.model

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import com.terraformation.backend.db.tables.references.PROJECTS
import org.jooq.Field
import org.jooq.Record

data class ProjectModel(
    val id: ProjectId,
    val organizationId: OrganizationId,
    val name: String,
    val sites: List<SiteModel>? = null
) {
  constructor(
      record: Record,
      sitesMultiset: Field<List<SiteModel>?>
  ) : this(
      record[PROJECTS.ID] ?: throw IllegalArgumentException("ID is required"),
      record[PROJECTS.ORGANIZATION_ID]
          ?: throw IllegalArgumentException("Organization is required"),
      record[PROJECTS.NAME] ?: throw IllegalArgumentException("Name is required"),
      record[sitesMultiset])
}

fun ProjectsRow.toModel() =
    ProjectModel(
        id ?: throw IllegalArgumentException("ID is required"),
        organizationId ?: throw IllegalArgumentException("Organization is required"),
        name ?: throw IllegalArgumentException("Name is required"))
