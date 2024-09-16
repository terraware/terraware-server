package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import java.time.Duration

/**
 * Published when a participant project species is added to a project.
 *
 * @see com.terraformation.backend.accelerator.SpeciesNotifier
 */
data class ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
) : RateLimitedEvent<ParticipantProjectSpeciesAddedToProjectNotificationDueEvent> {
  override fun getRateLimitKey() = projectId

  override fun getMinimumInterval(): Duration = Duration.ofMinutes(10)
}
