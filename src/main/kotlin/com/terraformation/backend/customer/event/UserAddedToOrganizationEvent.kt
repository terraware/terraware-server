package com.terraformation.backend.customer.event

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId

data class UserAddedToOrganizationEvent(
    val userId: UserId,
    val organizationId: OrganizationId,
    val addedBy: UserId,
)
