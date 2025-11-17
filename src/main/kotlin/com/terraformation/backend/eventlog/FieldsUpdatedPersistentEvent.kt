package com.terraformation.backend.eventlog

/**
 * Common interface for events that represent one or more fields of an entity being updated. This is
 * used to render [PersistentEvent]s into API payloads for event log queries. Update events should
 * either implement this interface or be handled explicitly in
 * [EventLogPayloadTransformer.getActions].
 *
 * The typical pattern for events that implement this interface is to define a nested class (usually
 * called `Values`) with a nullable property for each of the fields that can change. The event class
 * has two instances of that class, typically called `changedFrom` and `changedTo`, and properties
 * that didn't change are set to null in both instances. Implementing this interface's
 * [listUpdatedFields] method is then a simple matter of scanning each `Values` property looking for
 * non-null values on either side.
 *
 * For example:
 * ```kotlin
 * data class ExampleEventV1(
 *   val changedFrom: Values,
 *   val changedTo: Values,
 *   val exampleId: ExampleID
 * ) : FieldsUpdatedPersistentEvent {
 *   data class Values(
 *     val colors: Set<ExampleColor>?,
 *     val description: String?,
 *   )
 *
 *   override fun listUpdatedFields(): List<UpdatedField> {
 *     return listOfNotNull(
 *       createUpdatedField(
 *         "colors",
 *         changedFrom.colors?.map { it.getDisplayName(currentUser().locale) },
 *         changedTo.colors?.map { it.getDisplayName(currentUser().locale) },
 *       ),
 *       createUpdatedField(
 *         "description",
 *         changedFrom.description,
 *         changedTo.description
 *       )
 *     )
 *   }
 * }
 * ```
 */
interface FieldsUpdatedPersistentEvent : EntityUpdatedPersistentEvent {
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
       * enum values. This value should be localized if applicable.
       */
      val changedFrom: List<String>?,
      /**
       * Previous value of the field, or null if the event represents the value being removed. For
       * scalar fields, this list will have one element, but it can have multiple elements if a
       * field is, e.g., a set of enum values. This value should be localized if applicable.
       */
      val changedTo: List<String>?,
  )

  /**
   * Returns an [UpdatedField] if either or both of [changedFrom] and [changedTo] are non-null, null
   * otherwise. This would typically be called in `listOfNotNull` in the [listUpdatedFields]
   * implementation.
   */
  fun createUpdatedField(
      fieldName: String,
      changedFrom: List<String>?,
      changedTo: List<String>?,
  ): UpdatedField? {
    return if (changedFrom != null || changedTo != null) {
      UpdatedField(fieldName, changedFrom, changedTo)
    } else {
      null
    }
  }

  /**
   * Returns an [UpdatedField] with [changedFrom] and [changedTo] wrapped in single-element lists if
   * either or both of them are non-null, null otherwise. This would typically be called in
   * `listOfNotNull` in the [listUpdatedFields] implementation.
   */
  fun createUpdatedField(
      fieldName: String,
      changedFrom: String?,
      changedTo: String?,
  ): UpdatedField? {
    return createUpdatedField(
        fieldName,
        changedFrom?.let { listOf(it) },
        changedTo?.let { listOf(it) },
    )
  }
}
