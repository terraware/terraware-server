package com.terraformation.backend.eventlog.model

import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.eventlog.PersistentEvent
import java.time.Instant

data class EventLogEntry<T : PersistentEvent>(
    val createdBy: UserId,
    val createdTime: Instant,
    val event: T,
    val id: EventLogId,
)
