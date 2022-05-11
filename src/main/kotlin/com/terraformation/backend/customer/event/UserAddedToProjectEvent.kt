package com.terraformation.backend.customer.event

import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId

data class UserAddedToProjectEvent(
    val userId: UserId,
    val projectId: ProjectId,
    val addedBy: UserId,
)
