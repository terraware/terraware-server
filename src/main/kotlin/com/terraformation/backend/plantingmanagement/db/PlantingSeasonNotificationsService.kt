package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_NOTIFICATIONS
import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationGroupModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationType
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonRelatedPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import jakarta.inject.Named
import kotlin.reflect.KClass
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class PlantingSeasonNotificationsService(
    private val dslContext: DSLContext,
    private val eventLogStore: EventLogStore,
) {
  private val inventoryPlanningNotificationEventClasses:
      List<KClass<out PlantingSeasonRelatedPersistentEvent>> =
      listOf(
          PlantingSeasonPersistentEvent::class,
          PlantingSeasonSpeciesTargetPersistentEvent::class,
      )

  private val plantingSeasonPlanningNotificationEventClasses:
      List<KClass<out PlantingSeasonRelatedPersistentEvent>> =
      listOf(
          PlantingSeasonAllocatedSpeciesPersistentEvent::class,
      )

  /**
   * Returns the undismissed notifications for every planting season in an organization. A season
   * that has never dismissed a notification has all of its events returned; a season with no
   * undismissed events is absent from the result.
   */
  fun getInventoryPlanningNotifications(
      organizationId: OrganizationId
  ): List<PlantingSeasonNotificationGroupModel> {
    val seasonInfoById =
        fetchSeasonInfoByCondition(
            PLANTING_SEASONS.plantingSites.ORGANIZATION_ID.eq(organizationId)
        )

    requirePermissions { seasonInfoById.keys.forEach { readPlantingSeason(it) } }

    if (seasonInfoById.isEmpty()) {
      return emptyList()
    }

    val entriesBySeason =
        eventLogStore
            .fetchByIdsSince(
                seasonInfoById.mapValues { it.value.lastDismissedEventLogId },
                inventoryPlanningNotificationEventClasses,
            )
            .groupBy { it.event.plantingSeasonId }

    val scientificNamesBySpeciesId =
        fetchScientificNamesBySpeciesId(speciesIdsOf(entriesBySeason.values.flatten()))

    return entriesBySeason.map { (plantingSeasonId, entries) ->
      buildNotificationModel(
          plantingSeasonId,
          entries,
          seasonInfoById.getValue(plantingSeasonId),
          scientificNamesBySpeciesId,
      )
    }
  }

  /**
   * Returns the undismissed notification for a single planting season, or null if the season has no
   * undismissed events. If the season has never dismissed a notification, all of its events are
   * returned.
   */
  fun getInventoryPlanningNotifications(
      plantingSeasonId: PlantingSeasonId
  ): PlantingSeasonNotificationGroupModel? =
      getPlantingSeasonNotifications(
          plantingSeasonId,
          inventoryPlanningNotificationEventClasses,
      )

  fun getPlantingSeasonPlanningNotifications(
      plantingSeasonId: PlantingSeasonId
  ): PlantingSeasonNotificationGroupModel? =
      getPlantingSeasonNotifications(
          plantingSeasonId,
          plantingSeasonPlanningNotificationEventClasses,
      )

  private fun getPlantingSeasonNotifications(
      plantingSeasonId: PlantingSeasonId,
      requestedClasses: List<KClass<out PlantingSeasonRelatedPersistentEvent>>,
  ): PlantingSeasonNotificationGroupModel? {
    requirePermissions { readPlantingSeason(plantingSeasonId) }

    val seasonInfoById = fetchSeasonInfoByCondition(PLANTING_SEASONS.ID.eq(plantingSeasonId))

    val entries =
        eventLogStore.fetchByIdsSince(
            seasonInfoById.mapValues { it.value.lastDismissedEventLogId },
            requestedClasses,
        )

    if (entries.isEmpty()) {
      return null
    }

    return buildNotificationModel(
        plantingSeasonId,
        entries,
        seasonInfoById.getValue(plantingSeasonId),
        fetchScientificNamesBySpeciesId(speciesIdsOf(entries)),
    )
  }

  private fun buildNotificationModel(
      plantingSeasonId: PlantingSeasonId,
      entries: List<EventLogEntry<PlantingSeasonRelatedPersistentEvent>>,
      seasonInfo: SeasonInfo,
      scientificNamesBySpeciesId: Map<SpeciesId, String>,
  ): PlantingSeasonNotificationGroupModel =
      PlantingSeasonNotificationGroupModel(
          plantingSeasonId = plantingSeasonId,
          plantingSeasonName = seasonInfo.plantingSeasonName,
          plantingSiteName = seasonInfo.plantingSiteName,
          lastEventLogId = entries.maxOf { it.id },
          notifications = combineEvents(entries.map { it.event }, scientificNamesBySpeciesId),
      )

  /**
   * Collapses the events for a single planting season into at most one notification per
   * [PlantingSeasonNotificationType]. Events that do not map to a notification type are dropped.
   * For a species target notification, the scientific names of every species referenced by the
   * combined events are gathered into [PlantingSeasonNotificationModel.speciesScientificNames].
   *
   * [events] is expected to be ordered by the time each event was logged, which is the order
   * returned by [EventLogStore.fetchByIdsSince]; notications are returned in the order their types
   * first appear.
   */
  private fun combineEvents(
      events: List<PlantingSeasonRelatedPersistentEvent>,
      scientificNamesBySpeciesId: Map<SpeciesId, String>,
  ): List<PlantingSeasonNotificationModel> {
    require(events.mapTo(mutableSetOf()) { it.plantingSeasonId }.size <= 1) {
      "combineEvents must be called with events for a single planting season"
    }

    val speciesNamesByType = linkedMapOf<PlantingSeasonNotificationType, MutableSet<String>>()

    events.forEach { event ->
      val type = event.notificationType ?: return@forEach
      val speciesNames = speciesNamesByType.getOrPut(type) { mutableSetOf() }
      if (event is PlantingSeasonSpeciesTargetPersistentEvent) {
        scientificNamesBySpeciesId[event.speciesId]?.let { speciesNames.add(it) }
      }
    }

    return speciesNamesByType.map { (type, speciesNames) ->
      PlantingSeasonNotificationModel(
          type = type,
          speciesScientificNames = speciesNames.ifEmpty { null },
      )
    }
  }

  private val PlantingSeasonRelatedPersistentEvent.notificationType: PlantingSeasonNotificationType?
    get() =
        when (this) {
          is PlantingSeasonSpeciesTargetCreatedEvent ->
              PlantingSeasonNotificationType.SpeciesTargetsAdded
          is PlantingSeasonSpeciesTargetUpdatedEvent ->
              PlantingSeasonNotificationType.SpeciesTargetsUpdated
          is PlantingSeasonUpdatedEvent ->
              if (changedTo.status == PlantingSeasonStatus.Closed) {
                PlantingSeasonNotificationType.PlantingSeasonClosed
              } else if (changedTo.status == PlantingSeasonStatus.PastEndDate) {
                PlantingSeasonNotificationType.PlantingSeasonPastEndDate
              } else {
                null
              }
          is PlantingSeasonAllocatedSpeciesPersistentEvent ->
              PlantingSeasonNotificationType.AllocationQuantitiesUpdated
          else -> null
        }

  private fun speciesIdsOf(
      entries: List<EventLogEntry<PlantingSeasonRelatedPersistentEvent>>
  ): Set<SpeciesId> =
      entries
          .map { it.event }
          .filterIsInstance<PlantingSeasonSpeciesTargetPersistentEvent>()
          .mapTo(mutableSetOf()) { it.speciesId }

  private fun fetchScientificNamesBySpeciesId(speciesIds: Set<SpeciesId>): Map<SpeciesId, String> {
    if (speciesIds.isEmpty()) {
      return emptyMap()
    }

    return dslContext
        .select(SPECIES.ID, SPECIES.SCIENTIFIC_NAME)
        .from(SPECIES)
        .where(SPECIES.ID.`in`(speciesIds))
        .associate { it[SPECIES.ID]!! to it[SPECIES.SCIENTIFIC_NAME]!! }
  }

  /**
   * Returns the dismissal watermark and display names for every planting season matching
   * [condition]. Every matching season is included; [SeasonInfo.lastDismissedEventLogId] is null
   * for seasons that have never dismissed a notification, which means all of their events should be
   * treated as undismissed.
   */
  private fun fetchSeasonInfoByCondition(condition: Condition): Map<PlantingSeasonId, SeasonInfo> =
      dslContext
          .select(
              PLANTING_SEASONS.ID,
              PLANTING_SEASONS.NAME,
              PLANTING_SEASONS.plantingSites.NAME,
              PLANTING_SEASON_NOTIFICATIONS.LAST_DISMISSED_EVENT_LOG_ID,
          )
          .from(PLANTING_SEASONS)
          .leftJoin(PLANTING_SEASON_NOTIFICATIONS)
          .on(PLANTING_SEASON_NOTIFICATIONS.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID))
          .where(condition)
          .associate { record ->
            record[PLANTING_SEASONS.ID]!! to
                SeasonInfo(
                    lastDismissedEventLogId =
                        record[PLANTING_SEASON_NOTIFICATIONS.LAST_DISMISSED_EVENT_LOG_ID],
                    plantingSeasonName = record[PLANTING_SEASONS.NAME]!!,
                    plantingSiteName = record[PLANTING_SEASONS.plantingSites.NAME]!!,
                )
          }

  private data class SeasonInfo(
      val lastDismissedEventLogId: EventLogId?,
      val plantingSeasonName: String,
      val plantingSiteName: String,
  )
}
