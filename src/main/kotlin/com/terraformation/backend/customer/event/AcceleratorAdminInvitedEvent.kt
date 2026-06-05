package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.UserId

data class AcceleratorAdminInvitedEvent(
    val email: String,
    val invitedBy: UserId,
    val userId: UserId,
)
