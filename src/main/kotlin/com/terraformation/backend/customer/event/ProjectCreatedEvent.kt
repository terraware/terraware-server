package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.eventlog.PersistentEvent

data class ProjectCreatedEventV1(
    val name: String,
    val organizationId: OrganizationId,
    val projectId: ProjectId,
) : PersistentEvent

typealias ProjectCreatedEvent = ProjectCreatedEventV1
