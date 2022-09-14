package com.terraformation.backend.customer.event

import com.terraformation.backend.db.UserId

data class UserDeletedEvent(val userId: UserId)
