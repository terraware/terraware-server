package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
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
