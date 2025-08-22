package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.UserId

class ProjectInternalUserModel(
    val userId: UserId,
    val role: ProjectInternalRole? = null,
    val roleName: String? = null,
)
