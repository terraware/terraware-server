package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.EventId

/** Published when an event is starting. */
data class ModuleEventStartingEvent(val eventId: EventId)
