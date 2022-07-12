package com.terraformation.backend.customer.model

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
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
