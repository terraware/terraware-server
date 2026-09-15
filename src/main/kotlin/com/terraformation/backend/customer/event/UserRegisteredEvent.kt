package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.UserId

/**
 * Published when a new user registers for Terraware, either as a result of being invited to an
 * organization or because they signed up on their own.
 */
data class UserRegisteredEvent(
    val userId: UserId,
)
