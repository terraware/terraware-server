package com.terraformation.backend.eventlog

/**
 * Common interface for events that represent one or more fields of an entity being updated. This is
 * used to render [PersistentEvent]s into API payloads for event log queries. Update events should
 * either implement this interface or be handled explicitly in
 * [EventLogPayloadTransformer.getActions].
 */
interface FieldsUpdatedEvent {
  /**
   * Returns a list of all the fields that were updated by the operation this event represents.
   * Fields that weren't modified shouldn't be included in the list. If the event represents a
   * single field change, this should return a list of length 1.
   */
  fun listUpdatedFields(): List<UpdatedField>

  /** Represents a change to a single field. */
  data class UpdatedField(
      /**
       * Canonical name of the field. This will generally be the same as the name of the Kotlin
       * property of the entity's model class. Do not use localized names here; localization of
       * field names is handled elsewhere.
       */
      val fieldName: String,
      /**
       * Previous value of the field, or null if it had no previous value. For scalar fields, this
       * list will have one element, but it can have multiple elements if a field is, e.g., a set of
       * enum values.
       */
      val changedFrom: List<String>?,
      /**
       * Previous value of the field, or null if the event represents the value being removed. For
       * scalar fields, this list will have one element, but it can have multiple elements if a
       * field is, e.g., a set of enum values.
       */
      val changedTo: List<String>?,
  ) {
    constructor(
        fieldName: String,
        changedFrom: String?,
        changedTo: String?,
    ) : this(fieldName, changedFrom?.let { listOf(it) }, changedTo?.let { listOf(it) })
  }
}
