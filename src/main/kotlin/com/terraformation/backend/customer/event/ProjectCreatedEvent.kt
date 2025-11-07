package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent

data class ProjectCreatedEventV1(
    val name: String,
    override val organizationId: OrganizationId,
    override val projectId: ProjectId,
) : EntityCreatedPersistentEvent, ProjectPersistentEvent

typealias ProjectCreatedEvent = ProjectCreatedEventV1
