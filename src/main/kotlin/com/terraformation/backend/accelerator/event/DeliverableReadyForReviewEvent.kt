package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import java.time.Duration

/** Published when a deliverable is ready for review */
data class DeliverableReadyForReviewEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
) : RateLimitedEvent<DeliverableReadyForReviewEvent> {
  override fun getRateLimitKey() = this

  override fun getMinimumInterval(): Duration = Duration.ofMinutes(5)
}
