package com.terraformation.backend.customer.event

import com.terraformation.backend.db.UserId

/**
 * Published when we start deleting a user's data from the database, but before the user has
 * actually been deleted.
 */
data class UserDeletionStartedEvent(val userId: UserId)
