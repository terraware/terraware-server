package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant

data class EditHistoryModel(
    val createdBy: UserId,
    val createdTime: Instant,
)
