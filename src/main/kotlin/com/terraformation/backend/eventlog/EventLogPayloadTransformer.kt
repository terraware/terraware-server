package com.terraformation.backend.eventlog

import com.terraformation.backend.customer.db.SimpleUserStore
import com.terraformation.backend.customer.event.OrganizationPersistentEvent
import com.terraformation.backend.customer.event.ProjectPersistentEvent
import com.terraformation.backend.eventlog.api.CreatedActionPayload
import com.terraformation.backend.eventlog.api.DeletedActionPayload
import com.terraformation.backend.eventlog.api.EventActionPayload
import com.terraformation.backend.eventlog.api.EventLogEntryPayload
import com.terraformation.backend.eventlog.api.EventSubjectName
import com.terraformation.backend.eventlog.api.EventSubjectPayload
import com.terraformation.backend.eventlog.api.FieldUpdatedActionPayload
import com.terraformation.backend.eventlog.api.ObservationPlotMediaSubjectPayload
import com.terraformation.backend.eventlog.api.OrganizationSubjectPayload
import com.terraformation.backend.eventlog.api.ProjectSubjectPayload
import com.terraformation.backend.eventlog.api.RecordedTreeSubjectPayload
import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.event.ObservationMediaFilePersistentEvent
import com.terraformation.backend.tracking.event.RecordedTreePersistentEvent
import jakarta.inject.Named

@Named
class EventLogPayloadTransformer(
    private val messages: Messages,
    private val simpleUserStore: SimpleUserStore,
) {
  private val log = perClassLogger()

  /**
   * Returns a list of [EventLogEntryPayload] objects representing a sequence of events. This is not
   * necessarily a 1:1 mapping; a single event can map to multiple payloads, e.g., if the event
   * describes an update of multiple fields.
   *
   * The logic that produces a payload for a given event can refer to earlier events in the list,
   * e.g., to pull an entity's name from its "created" event. Therefore, it's important that
   * [entries] include all the events for each entity whose events are included. This should happen
   * automatically if the list of events is queried using the event interfaces from
   * [EventSubjectName].
   *
   * The list of entries must be in chronological order; the query methods in [EventLogStore] return
   * chronological results, so this usually doesn't require any additional work.
   */
  fun eventsToPayloads(entries: List<EventLogEntry<PersistentEvent>>): List<EventLogEntryPayload> {
    val context = EventLogPayloadContext(entries, messages)
    val users = simpleUserStore.fetchSimpleUsersById(entries.map { it.createdBy }.distinct())

    return entries.flatMap { entry ->
      val actions = getActions(entry.event, context)
      val subject = getSubject(entry.event, context)

      if (actions.isNotEmpty() && subject != null) {
        actions.map { action ->
          EventLogEntryPayload(
              action = localizeFieldName(subject, action),
              subject = subject,
              timestamp = entry.createdTime,
              userId = entry.createdBy,
              userName = users[entry.createdBy]?.fullName ?: messages.formerUser(),
          )
        }
      } else {
        emptyList()
      }
    }
  }

  private fun getSubject(
      event: PersistentEvent,
      context: EventLogPayloadContext,
  ): EventSubjectPayload? {
    return when (event) {
      is ObservationMediaFilePersistentEvent ->
          ObservationPlotMediaSubjectPayload.forEvent(event, context)
      is OrganizationPersistentEvent -> OrganizationSubjectPayload.forEvent(event, context)
      is ProjectPersistentEvent -> ProjectSubjectPayload.forEvent(event, context)
      is RecordedTreePersistentEvent -> RecordedTreeSubjectPayload.forEvent(event, context)
      else -> {
        log.error("Cannot construct subject for event ${event.javaClass.name}")
        null
      }
    }
  }

  private fun getActions(
      event: PersistentEvent,
      context: EventLogPayloadContext,
  ): List<EventActionPayload> {
    return when (event) {
      // Any fields-updated events that don't implement FieldsUpdatedPersistentEvent should be
      // handled here.

      // (Currently there aren't any.)

      // Events that can self-describe which fields were updated can be transformed generically.
      is FieldsUpdatedPersistentEvent ->
          event.listUpdatedFields().map {
            FieldUpdatedActionPayload(it.fieldName, it.changedFrom, it.changedTo)
          }

      // Create and delete actions don't need any event-type-specific arguments because the details
      // are already in the subject.
      is EntityCreatedPersistentEvent -> listOf(CreatedActionPayload())
      is EntityDeletedPersistentEvent -> listOf(DeletedActionPayload())

      else -> {
        log.error("Cannot construct actions for event ${event.javaClass.name}")
        emptyList()
      }
    }
  }

  private fun localizeFieldName(
      subject: EventSubjectPayload,
      action: EventActionPayload,
  ): EventActionPayload =
      if (action is FieldUpdatedActionPayload) {
        action.copy(fieldName = messages.eventSubjectFieldName(subject::class, action.fieldName))
      } else {
        action
      }
}
