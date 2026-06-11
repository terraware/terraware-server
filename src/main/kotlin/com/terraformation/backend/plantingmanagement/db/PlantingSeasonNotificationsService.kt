package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_NOTIFICATIONS
import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEventValues
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class PlantingSeasonNotificationsService(
    private val dslContext: DSLContext,
    private val eventLogStore: EventLogStore,
) {

  /**
   * Returns the undismissed events for every planting season in an organization, grouped by
   * planting season. A season that has never dismissed a notification has all of its events
   * returned; a season with no undismissed events is absent from the result.
   */
  fun getNotifications(
      organizationId: OrganizationId
  ): Map<PlantingSeasonId, List<EventLogEntry<PlantingSeasonPersistentEvent>>> {
    val dismissedEventIds =
        fetchLastDismissedEventLogIdsByCondition(
            PLANTING_SEASONS.plantingSites.ORGANIZATION_ID.eq(organizationId)
        )

    requirePermissions { dismissedEventIds.keys.forEach { readPlantingSeason(it) } }

    if (dismissedEventIds.isEmpty()) {
      return emptyMap()
    }

    return eventLogStore
        .fetchByIdsSince(dismissedEventIds, listOf(PlantingSeasonPersistentEvent::class))
        .groupBy { it.event.plantingSeasonId }
        .mapValues { (_, events) -> combineEvents(events) }
  }

  /**
   * Returns the undismissed events for a single planting season, ordered by the time they were
   * logged. If the season has never dismissed a notification, all of its events are returned.
   */
  fun getNotifications(
      plantingSeasonId: PlantingSeasonId
  ): List<EventLogEntry<PlantingSeasonPersistentEvent>> {
    requirePermissions { readPlantingSeason(plantingSeasonId) }

    val dismissedEventIds =
        fetchLastDismissedEventLogIdsByCondition(PLANTING_SEASONS.ID.eq(plantingSeasonId))

    return combineEvents(
        eventLogStore.fetchByIdsSince(
            dismissedEventIds,
            listOf(PlantingSeasonPersistentEvent::class),
        )
    )
  }

  /**
   * Collapses the update events for a planting season into a single event, so that the result
   * contains at most one update notification. For every field, the combined event keeps the
   * [changedFrom][PlantingSeasonUpdatedEvent.changedFrom] value from the earliest update that
   * changed it and the [changedTo][PlantingSeasonUpdatedEvent.changedTo] value from the latest
   * update that changed it.
   *
   * Updates that change the status to [PlantingSeasonStatus.Closed] are excluded from the combined
   * event and remain as separate events in the result. Non-update events are also left untouched.
   *
   * [entries] is expected to be ordered by the time each event was logged, which is the order
   * returned by [EventLogStore.fetchByIdsSince].
   */
  private fun combineEvents(
      entries: List<EventLogEntry<PlantingSeasonPersistentEvent>>
  ): List<EventLogEntry<PlantingSeasonPersistentEvent>> {
    require(entries.mapTo(mutableSetOf()) { it.event.plantingSeasonId }.size <= 1) {
      "combineEvents must be called with entries for a single planting season"
    }

    val combinableUpdates = entries.filter {
      val event = it.event
      event is PlantingSeasonUpdatedEvent && event.changedTo.status != PlantingSeasonStatus.Closed
    }

    if (combinableUpdates.size < 2) {
      return entries
    }

    val updateEvents = combinableUpdates.map { it.event as PlantingSeasonUpdatedEvent }
    val combinedFrom =
        PlantingSeasonUpdatedEventValues(
            endDate = updateEvents.firstNotNullOfOrNull { it.changedFrom.endDate },
            name = updateEvents.firstNotNullOfOrNull { it.changedFrom.name },
            startDate = updateEvents.firstNotNullOfOrNull { it.changedFrom.startDate },
            status = updateEvents.firstNotNullOfOrNull { it.changedFrom.status },
        )
    val combinedTo =
        PlantingSeasonUpdatedEventValues(
            endDate = updateEvents.lastNotNullOfOrNull { it.changedTo.endDate },
            name = updateEvents.lastNotNullOfOrNull { it.changedTo.name },
            startDate = updateEvents.lastNotNullOfOrNull { it.changedTo.startDate },
            status = updateEvents.lastNotNullOfOrNull { it.changedTo.status },
        )

    val lastUpdateEntry = combinableUpdates.last()
    val combinedEntry =
        lastUpdateEntry.copy(
            event = updateEvents.last().copy(changedFrom = combinedFrom, changedTo = combinedTo)
        )

    return entries.mapNotNull { entry ->
      val event = entry.event
      when {
        event !is PlantingSeasonUpdatedEvent -> entry
        event.changedTo.status == PlantingSeasonStatus.Closed -> entry
        entry.id == lastUpdateEntry.id -> combinedEntry
        else -> null
      }
    }
  }

  private fun <T, R> List<T>.lastNotNullOfOrNull(transform: (T) -> R?): R? =
      asReversed().firstNotNullOfOrNull(transform)

  /**
   * Returns the dismissal watermark for every planting season matching [condition]. Every matching
   * season is included; the value is null for seasons that have never dismissed a notification,
   * which means all of their events should be treated as undismissed.
   */
  private fun fetchLastDismissedEventLogIdsByCondition(
      condition: Condition
  ): Map<PlantingSeasonId, EventLogId?> =
      dslContext
          .select(
              PLANTING_SEASONS.ID,
              PLANTING_SEASON_NOTIFICATIONS.LAST_DISMISSED_EVENT_LOG_ID,
          )
          .from(PLANTING_SEASONS)
          .leftJoin(PLANTING_SEASON_NOTIFICATIONS)
          .on(PLANTING_SEASON_NOTIFICATIONS.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID))
          .where(condition)
          .associate { record ->
            record[PLANTING_SEASONS.ID]!! to
                record[PLANTING_SEASON_NOTIFICATIONS.LAST_DISMISSED_EVENT_LOG_ID]
          }
}
