package com.terraformation.backend.customer.model

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectStatus
import com.terraformation.backend.db.ProjectType
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import com.terraformation.backend.db.tables.references.PROJECTS
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class ProjectModel(
    val createdTime: Instant,
    val description: String?,
    val id: ProjectId,
    val organizationId: OrganizationId,
    val name: String,
    val sites: List<SiteModel>? = null,
    val startDate: LocalDate?,
    val status: ProjectStatus?,
    val types: Set<ProjectType> = emptySet()
) {
  constructor(
      record: Record,
      sitesMultiset: Field<List<SiteModel>?>? = null,
      typesMultiset: Field<List<ProjectType>?>,
  ) : this(
      createdTime = record[PROJECTS.CREATED_TIME]
              ?: throw IllegalArgumentException("Created time is required"),
      description = record[PROJECTS.DESCRIPTION],
      id = record[PROJECTS.ID] ?: throw IllegalArgumentException("ID is required"),
      organizationId = record[PROJECTS.ORGANIZATION_ID]
              ?: throw IllegalArgumentException("Organization is required"),
      name = record[PROJECTS.NAME] ?: throw IllegalArgumentException("Name is required"),
      sites = sitesMultiset?.let { record[it] },
      startDate = record[PROJECTS.START_DATE],
      status = record[PROJECTS.STATUS_ID],
      types = record[typesMultiset]?.toSet() ?: emptySet(),
  )
}

fun ProjectsRow.toModel(types: Set<ProjectType> = emptySet()) =
    ProjectModel(
        createdTime = createdTime ?: throw IllegalArgumentException("Created time is required"),
        description = description,
        id = id ?: throw IllegalArgumentException("ID is required"),
        organizationId = organizationId
                ?: throw IllegalArgumentException("Organization is required"),
        name = name ?: throw IllegalArgumentException("Name is required"),
        startDate = startDate,
        status = statusId,
        types = types,
    )
