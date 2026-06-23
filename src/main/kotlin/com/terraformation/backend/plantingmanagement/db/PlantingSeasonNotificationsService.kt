package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonNotificationPage
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_NOTIFICATIONS
import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationGroupModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationType
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonRelatedPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonWithdrawalCreatedEvent
import jakarta.inject.Named
import kotlin.reflect.KClass
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class PlantingSeasonNotificationsService(
    private val dslContext: DSLContext,
    private val eventLogStore: EventLogStore,
) {
  private val eventClassesByNotificationType:
      Map<PlantingSeasonNotificationType, KClass<out PlantingSeasonRelatedPersistentEvent>> =
      mapOf(
          PlantingSeasonNotificationType.AllocationQuantitiesUpdated to
              PlantingSeasonAllocatedSpeciesPersistentEvent::class,
          PlantingSeasonNotificationType.PlantingSeasonClosed to
              PlantingSeasonPersistentEvent::class,
          PlantingSeasonNotificationType.PlantingSeasonPastEndDate to
              PlantingSeasonPersistentEvent::class,
          PlantingSeasonNotificationType.ScheduledPlantingDateRequested to
              PlantingDateRequestPersistentEvent::class,
          PlantingSeasonNotificationType.SeasonWithdrawalRecorded to
              PlantingSeasonWithdrawalCreatedEvent::class,
          PlantingSeasonNotificationType.SpeciesTargetsAdded to
              PlantingSeasonSpeciesTargetPersistentEvent::class,
          PlantingSeasonNotificationType.SpeciesTargetsUpdated to
              PlantingSeasonSpeciesTargetPersistentEvent::class,
      )

  /**
   * Returns the undismissed notifications for [page] for every planting season in [organizationId],
   * grouped by planting season.
   *
   * A season that has never dismissed a notification has all of its events returned; a season with
   * no undismissed events for [page] is absent from the result.
   */
  fun getNotifications(
      organizationId: OrganizationId,
      page: PlantingSeasonNotificationPage,
  ): List<PlantingSeasonNotificationGroupModel> =
      getNotifications(
          PLANTING_SEASONS.plantingSites.ORGANIZATION_ID.eq(organizationId),
          page,
      )

  /**
   * Returns the undismissed notifications for [page] for [plantingSeasonId], grouped by planting
   * season.
   *
   * A season that has never dismissed a notification has all of its events returned; a season with
   * no undismissed events for [page] is absent from the result.
   */
  fun getNotifications(
      plantingSeasonId: PlantingSeasonId,
      page: PlantingSeasonNotificationPage,
  ): List<PlantingSeasonNotificationGroupModel> =
      getNotifications(PLANTING_SEASONS.ID.eq(plantingSeasonId), page)

  private fun getNotifications(
      condition: Condition,
      page: PlantingSeasonNotificationPage,
  ): List<PlantingSeasonNotificationGroupModel> {
    val requestedTypes = notificationTypesForPage(page)
    val eventClasses = eventClassesToFetch(requestedTypes)
    if (eventClasses.isEmpty()) {
      return emptyList()
    }

    val seasonInfoById = fetchSeasonInfoByCondition(condition, page)

    requirePermissions { seasonInfoById.keys.forEach { readPlantingSeason(it) } }

    if (seasonInfoById.isEmpty()) {
      return emptyList()
    }

    val entriesBySeason =
        eventLogStore
            .fetchByIdsSince(
                seasonInfoById.mapValues { it.value.lastDismissedEventLogId },
                eventClasses,
            )
            .groupBy { it.event.plantingSeasonId }

    val scientificNamesBySpeciesId =
        fetchScientificNamesBySpeciesId(speciesIdsOf(entriesBySeason.values.flatten()))

    return entriesBySeason.mapNotNull { (plantingSeasonId, entries) ->
      buildNotificationModel(
          plantingSeasonId,
          entries,
          seasonInfoById.getValue(plantingSeasonId),
          scientificNamesBySpeciesId,
          page,
          requestedTypes,
      )
    }
  }

  private fun eventClassesToFetch(
      types: Set<PlantingSeasonNotificationType>
  ): List<KClass<out PlantingSeasonRelatedPersistentEvent>> =
      types.mapNotNull { eventClassesByNotificationType[it] }.distinct()

  private fun notificationTypesForPage(
      page: PlantingSeasonNotificationPage
  ): Set<PlantingSeasonNotificationType> =
      when (page) {
        PlantingSeasonNotificationPage.InventoryPlanning ->
            setOf(
                PlantingSeasonNotificationType.PlantingSeasonClosed,
                PlantingSeasonNotificationType.SpeciesTargetsAdded,
                PlantingSeasonNotificationType.SpeciesTargetsUpdated,
            )
        PlantingSeasonNotificationPage.PlantingSeasonPlanning ->
            setOf(
                PlantingSeasonNotificationType.AllocationQuantitiesUpdated,
                PlantingSeasonNotificationType.SeasonWithdrawalRecorded,
                PlantingSeasonNotificationType.PlantingSeasonPastEndDate,
            )
        PlantingSeasonNotificationPage.Inventory ->
            setOf(
                PlantingSeasonNotificationType.SpeciesTargetsAdded,
                PlantingSeasonNotificationType.SpeciesTargetsUpdated,
                PlantingSeasonNotificationType.PlantingSeasonClosed,
                PlantingSeasonNotificationType.ScheduledPlantingDateRequested,
            )
        PlantingSeasonNotificationPage.Withdrawals ->
            setOf(
                PlantingSeasonNotificationType.ScheduledPlantingDateRequested,
                PlantingSeasonNotificationType.PlantingSeasonClosed,
            )
      }

  private fun buildNotificationModel(
      plantingSeasonId: PlantingSeasonId,
      entries: List<EventLogEntry<PlantingSeasonRelatedPersistentEvent>>,
      seasonInfo: SeasonInfo,
      scientificNamesBySpeciesId: Map<SpeciesId, String>,
      page: PlantingSeasonNotificationPage,
      requestedTypes: Set<PlantingSeasonNotificationType>,
  ): PlantingSeasonNotificationGroupModel? {
    val notifications =
        combineEvents(entries.map { it.event }, scientificNamesBySpeciesId, requestedTypes)
    if (notifications.isEmpty()) {
      return null
    }

    return PlantingSeasonNotificationGroupModel(
        plantingSeasonId = plantingSeasonId,
        plantingSeasonName = seasonInfo.plantingSeasonName,
        plantingSiteName = seasonInfo.plantingSiteName,
        lastEventLogId = entries.maxOf { it.id },
        notificationPage = page,
        notifications = notifications,
    )
  }

  /**
   * Collapses the events for a single planting season into at most one notification per
   * [PlantingSeasonNotificationType]. Events that do not map to a notification type, or whose type
   * is not in [requestedTypes], are dropped. For a species target notification, the scientific
   * names of every species referenced by the combined events are gathered into
   * [PlantingSeasonNotificationModel.speciesScientificNames].
   *
   * [events] is expected to be ordered by the time each event was logged, which is the order
   * returned by [EventLogStore.fetchByIdsSince]; notications are returned in the order their types
   * first appear.
   */
  private fun combineEvents(
      events: List<PlantingSeasonRelatedPersistentEvent>,
      scientificNamesBySpeciesId: Map<SpeciesId, String>,
      requestedTypes: Set<PlantingSeasonNotificationType>,
  ): List<PlantingSeasonNotificationModel> {
    require(events.mapTo(mutableSetOf()) { it.plantingSeasonId }.size <= 1) {
      "combineEvents must be called with events for a single planting season"
    }

    val speciesNamesByType = linkedMapOf<PlantingSeasonNotificationType, MutableSet<String>>()

    events.forEach { event ->
      val type = event.notificationType ?: return@forEach
      if (type !in requestedTypes) return@forEach
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
          is PlantingSeasonAllocatedSpeciesPersistentEvent ->
              PlantingSeasonNotificationType.AllocationQuantitiesUpdated
          is PlantingDateRequestPersistentEvent ->
              PlantingSeasonNotificationType.ScheduledPlantingDateRequested
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
          is PlantingSeasonWithdrawalCreatedEvent ->
              PlantingSeasonNotificationType.SeasonWithdrawalRecorded
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
   * for seasons that have never dismissed a notification for [page], which means all of their
   * events for that page should be treated as undismissed.
   */
  private fun fetchSeasonInfoByCondition(
      condition: Condition,
      page: PlantingSeasonNotificationPage,
  ): Map<PlantingSeasonId, SeasonInfo> =
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
          .and(PLANTING_SEASON_NOTIFICATIONS.PAGE_ID.eq(page))
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
