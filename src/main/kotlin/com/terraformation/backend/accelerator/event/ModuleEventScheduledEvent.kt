package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.EventId

/** Published when an event is scheduled or rescheduled. */
data class ModuleEventScheduledEvent(val eventId: EventId, val revision: Int)
