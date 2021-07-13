package com.terraformation.backend.customer.model

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.tables.pojos.ProjectsRow

data class ProjectModel(val id: ProjectId, val organizationId: OrganizationId, val name: String)

fun ProjectsRow.toModel() =
    ProjectModel(
        id ?: throw IllegalArgumentException("ID is required"),
        organizationId ?: throw IllegalArgumentException("Organization is required"),
        name ?: throw IllegalArgumentException("Name is required"))
