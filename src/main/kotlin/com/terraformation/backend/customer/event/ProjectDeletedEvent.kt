package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.eventlog.PersistentEvent

data class ProjectDeletedEventV1(
    val organizationId: OrganizationId,
    val projectId: ProjectId,
) : PersistentEvent

typealias ProjectDeletedEvent = ProjectDeletedEventV1
