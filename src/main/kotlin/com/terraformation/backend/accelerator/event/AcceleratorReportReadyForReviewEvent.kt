package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import java.time.Duration

/** Published when a report is ready for review */
data class AcceleratorReportReadyForReviewEvent(
    val reportId: ReportId,
) : RateLimitedEvent<AcceleratorReportReadyForReviewEvent> {
  override fun getRateLimitKey() = this

  override fun getMinimumInterval(): Duration = Duration.ofMinutes(5)
}
