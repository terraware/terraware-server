package com.terraformation.backend.eventlog.api

import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant

data class EventLogEntryPayload(
    val action: EventActionPayload,
    val subject: EventSubjectPayload,
    val timestamp: Instant,
    val userId: UserId,
    val userName: String,
) {
  constructor(
      action: EventActionPayload,
      subject: EventSubjectPayload,
      timestamp: Instant,
      user: SimpleUserModel,
  ) : this(action, subject, timestamp, user.userId, user.fullName)
}
