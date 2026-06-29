package com.terraformation.backend.eventlog

import com.terraformation.backend.i18n.Messages

/**
 * Common interface for events that represent an entity being created with specific initial field
 * values. This is used to render [PersistentEvent]s into API payloads for event log queries.
 * Creation events that implement this interface will produce a
 * [CreatedActionPayload][com.terraformation.backend.eventlog.api.CreatedActionPayload] that
 * includes the initial field values; those that don't will produce a plain
 * [CreatedActionPayload][com.terraformation.backend.eventlog.api.CreatedActionPayload] with no
 * field data.
 *
 * The typical pattern is to define a nested class (usually called `Values`) with a nullable
 * property for each field whose initial value should be surfaced. Implementing [listInitialFields]
 * is then a matter of scanning those properties and returning an [InitialField] for each non-null
 * one. Use [createInitialField] as a helper to skip null values cleanly inside `listOfNotNull`.
 *
 * Fields that don't need to appear in the event log can simply be omitted from `Values` or left
 * null, and they won't show up in the payload.
 *
 * For example:
 * ```kotlin
 * data class ExampleCreatedEventV1(
 *   val values: Values,
 *   val exampleId: ExampleID
 * ) : FieldsCreatedPersistentEvent {
 *   data class Values(
 *     val quantity: Int?,
 *   )
 *
 *   override fun listInitialFields(messages: Messages): List<InitialField> {
 *     return listOfNotNull(
 *       createInitialField("quantity", values.quantity?.toString()),
 *     )
 *   }
 * }
 * ```
 */
interface FieldsCreatedPersistentEvent : EntityCreatedPersistentEvent {
  /**
   * Returns a list of the fields whose initial values should be included in the event log payload.
   * Fields that don't need to be surfaced can be omitted.
   */
  fun listInitialFields(messages: Messages): List<InitialField>

  /** Represents the initial value of a single field at entity creation time. */
  data class InitialField(
      /**
       * Canonical name of the field. This will generally be the same as the name of the Kotlin
       * property of the entity's model class. Do not use localized names here; localization of
       * field names is handled elsewhere.
       */
      val fieldName: String,
      /**
       * Initial value of the field, or null if the field had no value at creation time. For scalar
       * fields this list will have one element, but it can have multiple elements if the field is,
       * e.g., a set of enum values. This value should be localized if applicable.
       */
      val value: List<String>?,
  )

  /**
   * Returns an [InitialField] if [value] is non-null, null otherwise. This would typically be
   * called in `listOfNotNull` in the [listInitialFields] implementation.
   */
  fun createInitialField(fieldName: String, value: List<String>?): InitialField? {
    return if (value != null) InitialField(fieldName, value) else null
  }

  /**
   * Returns an [InitialField] with [value] wrapped in a single-element list if [value] is non-null,
   * null otherwise. This would typically be called in `listOfNotNull` in the [listInitialFields]
   * implementation.
   */
  fun createInitialField(fieldName: String, value: String?): InitialField? {
    return createInitialField(fieldName, value?.let { listOf(it) })
  }
}
