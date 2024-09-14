package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import java.time.Duration

/**
 * Published when an approved participant project species is edited.
 *
 * @see com.terraformation.backend.accelerator.SpeciesNotifier
 */
data class ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
) : RateLimitedEvent<ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent> {
  override fun getRateLimitKey() = projectId

  override fun getMinimumInterval(): Duration = Duration.ofMinutes(10)
}
