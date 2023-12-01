package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a project's name is changed. */
data class ProjectRenamedEvent(
    val projectId: ProjectId,
    val oldName: String,
    val newName: String,
)
