package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.eventlog.EntityUpdatedPersistentEvent

/** Published when a project's name is changed. */
data class ProjectRenamedEventV1(
    val name: String,
    override val organizationId: OrganizationId,
    override val projectId: ProjectId,
) : EntityUpdatedPersistentEvent, ProjectPersistentEvent

typealias ProjectRenamedEvent = ProjectRenamedEventV1
