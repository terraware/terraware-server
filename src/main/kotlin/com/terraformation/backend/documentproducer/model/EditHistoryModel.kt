package com.terraformation.pdd.document.model

import com.terraformation.pdd.jooq.UserId
import java.time.Instant

data class EditHistoryModel(
    val createdBy: UserId,
    val createdTime: Instant,
)
