package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import java.time.Instant

data class OrganizationUserModel(
    val userId: UserId,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val userType: UserType,
    val createdTime: Instant,
    val organizationId: OrganizationId,
    val role: Role,
)
