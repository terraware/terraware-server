package com.terraformation.backend.eventlog

/**
 * Marker interface for entity creation events. This is used to map the event to a "Created" action
 * in the event log query API.
 */
interface EntityCreatedPersistentEvent : PersistentEvent

/**
 * Marker interface for entity deletion events. This is used to map the event to a "Deleted" action
 * in the event log query API.
 */
interface EntityDeletedPersistentEvent : PersistentEvent

/** Marker interface for entity update events. */
interface EntityUpdatedPersistentEvent : PersistentEvent
