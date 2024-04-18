package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.EventId
import java.time.Duration
import java.time.Instant

/** Published when an event is starting. Ignored if event has been rescheduled. */
data class ModuleEventStartingEvent(
    val eventId: EventId,
    val leadTime: Duration,
    val startTime: Instant
)
