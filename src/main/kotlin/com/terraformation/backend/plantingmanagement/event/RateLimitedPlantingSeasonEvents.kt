package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import java.time.Duration

private val plantingSeasonNotificationInitialDelay: Duration = Duration.ofMinutes(15)
private val plantingSeasonNotificationMinimumInterval: Duration = Duration.ofHours(24)

/**
 * Published when species are added to or removed from a planting season's species targets by a user
 * edit. A species is added when it gets its first target in any substratum, and removed when its
 * last target is deleted.
 */
data class RateLimitedPlantingSeasonSpeciesChangedEvent(
    val addedSpeciesIds: Set<SpeciesId> = emptySet(),
    val organizationId: OrganizationId,
    val plantingSeasonId: PlantingSeasonId,
    val removedSpeciesIds: Set<SpeciesId> = emptySet(),
) : RateLimitedEvent<RateLimitedPlantingSeasonSpeciesChangedEvent> {
  override fun getRateLimitKey() = mapOf("plantingSeasonId" to plantingSeasonId)

  override fun getMinimumInterval(): Duration = plantingSeasonNotificationMinimumInterval

  override fun getInitialDelay(): Duration = plantingSeasonNotificationInitialDelay

  override fun combine(
      existing: RateLimitedPlantingSeasonSpeciesChangedEvent
  ): RateLimitedPlantingSeasonSpeciesChangedEvent {
    require(existing.plantingSeasonId == plantingSeasonId) {
      "Cannot combine events for different plantingSeasonIds"
    }

    return existing.copy(
        addedSpeciesIds =
            (existing.addedSpeciesIds - removedSpeciesIds) +
                (addedSpeciesIds - existing.removedSpeciesIds),
        removedSpeciesIds =
            (existing.removedSpeciesIds - addedSpeciesIds) +
                (removedSpeciesIds - existing.addedSpeciesIds),
    )
  }
}

/**
 * Published when a user edit changes the target quantity of a species that remains in a planting
 * season. Adding a target for a species in a new substratum, or deleting one of several targets for
 * a species, is treated as a change from or to 0.
 */
data class RateLimitedPlantingSeasonTargetQuantitiesChangedEvent(
    val changes: List<Change>,
    val organizationId: OrganizationId,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
) : RateLimitedEvent<RateLimitedPlantingSeasonTargetQuantitiesChangedEvent> {
  data class Change(
      val newQuantity: Int,
      val oldQuantity: Int,
      val speciesId: SpeciesId,
      val stratumName: String,
      val substratumId: SubstratumId,
      val substratumName: String,
  )

  override fun getRateLimitKey() = mapOf("plantingSeasonId" to plantingSeasonId)

  override fun getMinimumInterval(): Duration = plantingSeasonNotificationMinimumInterval

  override fun getInitialDelay(): Duration = plantingSeasonNotificationInitialDelay

  override fun combine(
      existing: RateLimitedPlantingSeasonTargetQuantitiesChangedEvent
  ): RateLimitedPlantingSeasonTargetQuantitiesChangedEvent {
    require(existing.plantingSeasonId == plantingSeasonId) {
      "Cannot combine events for different plantingSeasonIds"
    }

    val combinedChanges =
        (existing.changes + changes)
            .groupBy { it.speciesId to it.substratumId }
            .values
            .map { it.last().copy(oldQuantity = it.first().oldQuantity) }
            .filter { it.oldQuantity != it.newQuantity }

    return existing.copy(changes = combinedChanges)
  }
}

/**
 * Published when a withdrawal request is submitted to the nursery for a planting date. Requests are
 * rate-limited separately for each planting date.
 */
data class RateLimitedPlantingDateRequestSubmittedEvent(
    val organizationId: OrganizationId,
    val plantingSeasonId: PlantingSeasonId,
    val scheduledPlantingDateId: ScheduledPlantingDateId,
) : RateLimitedEvent<RateLimitedPlantingDateRequestSubmittedEvent> {
  override fun getRateLimitKey() =
      mapOf(
          "plantingSeasonId" to plantingSeasonId,
          "scheduledPlantingDateId" to scheduledPlantingDateId,
      )

  override fun getMinimumInterval(): Duration = plantingSeasonNotificationMinimumInterval

  override fun getInitialDelay(): Duration = plantingSeasonNotificationInitialDelay

  override fun combine(
      existing: RateLimitedPlantingDateRequestSubmittedEvent
  ): RateLimitedPlantingDateRequestSubmittedEvent = this
}
