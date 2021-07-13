package com.terraformation.backend.customer.model

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId

data class OrganizationUserModel(
    val userId: UserId,
    val authId: String,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val organizationId: OrganizationId,
    val role: Role,
    val projectIds: List<ProjectId>
)
