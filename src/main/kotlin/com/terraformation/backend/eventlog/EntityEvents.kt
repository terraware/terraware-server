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

/**
 * Marker interface for entity update events. If possible, events should implement
 * [FieldsUpdatedPersistentEvent] which extends this interface. Classes that implement this
 * interface but not that one must be explicitly handled in [EventLogPayloadTransformer.getActions].
 */
interface EntityUpdatedPersistentEvent : PersistentEvent
