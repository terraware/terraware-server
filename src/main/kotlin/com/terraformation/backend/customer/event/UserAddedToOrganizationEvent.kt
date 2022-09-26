package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId

data class UserAddedToOrganizationEvent(
    val userId: UserId,
    val organizationId: OrganizationId,
    val addedBy: UserId,
)
