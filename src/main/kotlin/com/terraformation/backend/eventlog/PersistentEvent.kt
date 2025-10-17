package com.terraformation.backend.eventlog

/** Implemented by events that should be saved to the event log when published. */
interface PersistentEvent {
  /**
   * Returns a human-readable version of this event suitable for logging to the console or Datadog.
   * If this returns null, a generic message will be logged.
   */
  fun toMessage(): String? = null
}
