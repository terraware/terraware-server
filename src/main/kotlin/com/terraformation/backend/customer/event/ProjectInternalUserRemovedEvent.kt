package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

data class ProjectInternalUserRemovedEvent(
    val projectId: ProjectId,
    val organizationId: OrganizationId,
    val userId: UserId,
)
