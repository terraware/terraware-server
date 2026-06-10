package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_NOTIFICATIONS
import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonRelatedPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEventValues
import jakarta.inject.Named
import kotlin.reflect.KClass
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class PlantingSeasonNotificationsService(
    private val dslContext: DSLContext,
    private val eventLogStore: EventLogStore,
) {
  private val notificationEventClasses: List<KClass<out PlantingSeasonRelatedPersistentEvent>> =
      listOf(
          PlantingSeasonPersistentEvent::class,
          PlantingSeasonSpeciesTargetPersistentEvent::class,
      )

  /**
   * Returns the undismissed events for every planting season in an organization, grouped by
   * planting season. A season that has never dismissed a notification has all of its events
   * returned; a season with no undismissed events is absent from the result.
   */
  fun getNotifications(
      organizationId: OrganizationId
  ): Map<PlantingSeasonId, List<PlantingSeasonRelatedPersistentEvent>> {
    val dismissedEventIds =
        fetchLastDismissedEventLogIdsByCondition(
            PLANTING_SEASONS.plantingSites.ORGANIZATION_ID.eq(organizationId)
        )

    requirePermissions { dismissedEventIds.keys.forEach { readPlantingSeason(it) } }

    if (dismissedEventIds.isEmpty()) {
      return emptyMap()
    }

    return eventLogStore
        .fetchByIdsSince(
            dismissedEventIds,
            notificationEventClasses,
        )
        .map { it.event }
        .groupBy { it.plantingSeasonId }
        .mapValues { (_, events) -> combineEvents(events) }
  }

  /**
   * Returns the undismissed events for a single planting season, ordered by the time they were
   * logged. If the season has never dismissed a notification, all of its events are returned.
   */
  fun getNotifications(
      plantingSeasonId: PlantingSeasonId
  ): List<PlantingSeasonRelatedPersistentEvent> {
    requirePermissions { readPlantingSeason(plantingSeasonId) }

    val dismissedEventIds =
        fetchLastDismissedEventLogIdsByCondition(PLANTING_SEASONS.ID.eq(plantingSeasonId))

    return combineEvents(
        eventLogStore
            .fetchByIdsSince(
                dismissedEventIds,
                notificationEventClasses,
            )
            .map { it.event }
    )
  }

  /**
   * Collapses the update events for a planting season into at most one update notification per kind
   * of update. Non-update events are left untouched.
   *
   * [events] is expected to be ordered by the time each event was logged, which is the order
   * returned by [EventLogStore.fetchByIdsSince].
   */
  private fun combineEvents(
      events: List<PlantingSeasonRelatedPersistentEvent>
  ): List<PlantingSeasonRelatedPersistentEvent> {
    require(events.mapTo(mutableSetOf()) { it.plantingSeasonId }.size <= 1) {
      "combineEvents must be called with events for a single planting season"
    }

    return combineSpeciesTargetUpdates(combineSeasonUpdates(events))
  }

  /**
   * Collapses the [PlantingSeasonUpdatedEvent]s for a season into a single event. For every field,
   * the combined event keeps the [changedFrom][PlantingSeasonUpdatedEvent.changedFrom] value from
   * the earliest update that changed it and the [changedTo][PlantingSeasonUpdatedEvent.changedTo]
   * value from the latest update that changed it.
   *
   * Updates that change the status to [PlantingSeasonStatus.Closed] are excluded from the combined
   * event and remain as separate events in the result.
   */
  private fun combineSeasonUpdates(
      events: List<PlantingSeasonRelatedPersistentEvent>
  ): List<PlantingSeasonRelatedPersistentEvent> {
    val combinableUpdates =
        events.filterIsInstance<PlantingSeasonUpdatedEvent>().filter {
          it.changedTo.status != PlantingSeasonStatus.Closed
        }

    if (combinableUpdates.size < 2) {
      return events
    }

    val combinedFrom =
        PlantingSeasonUpdatedEventValues(
            endDate = combinableUpdates.firstNotNullOfOrNull { it.changedFrom.endDate },
            name = combinableUpdates.firstNotNullOfOrNull { it.changedFrom.name },
            startDate = combinableUpdates.firstNotNullOfOrNull { it.changedFrom.startDate },
            status = combinableUpdates.firstNotNullOfOrNull { it.changedFrom.status },
        )
    val combinedTo =
        PlantingSeasonUpdatedEventValues(
            endDate = combinableUpdates.lastNotNullOfOrNull { it.changedTo.endDate },
            name = combinableUpdates.lastNotNullOfOrNull { it.changedTo.name },
            startDate = combinableUpdates.lastNotNullOfOrNull { it.changedTo.startDate },
            status = combinableUpdates.lastNotNullOfOrNull { it.changedTo.status },
        )

    val lastCombinable = combinableUpdates.last()

    return events.mapNotNull { event ->
      when {
        event !is PlantingSeasonUpdatedEvent -> event
        event.changedTo.status == PlantingSeasonStatus.Closed -> event
        event === lastCombinable -> event.copy(changedFrom = combinedFrom, changedTo = combinedTo)
        else -> null
      }
    }
  }

  /**
   * Collapses the [PlantingSeasonSpeciesTargetUpdatedEvent]s for each species target into a single
   * event, keeping the [changedFrom][PlantingSeasonSpeciesTargetUpdatedEvent.changedFrom] of the
   * earliest update and the [changedTo][PlantingSeasonSpeciesTargetUpdatedEvent.changedTo] of the
   * latest update. Only updates to the same species and substratum are combined; the combined event
   * keeps that target's last update as a representative.
   */
  private fun combineSpeciesTargetUpdates(
      events: List<PlantingSeasonRelatedPersistentEvent>
  ): List<PlantingSeasonRelatedPersistentEvent> {
    val updatesByTarget =
        events
            .filterIsInstance<PlantingSeasonSpeciesTargetUpdatedEvent>()
            .groupBy { it.speciesId to it.substratumId }
            .filterValues { it.size >= 2 }

    if (updatesByTarget.isEmpty()) {
      return events
    }

    return events.mapNotNull { event ->
      if (event !is PlantingSeasonSpeciesTargetUpdatedEvent) {
        event
      } else {
        val group = updatesByTarget[event.speciesId to event.substratumId]
        when {
          group == null -> event
          event === group.last() -> group.last().copy(changedFrom = group.first().changedFrom)
          else -> null
        }
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
