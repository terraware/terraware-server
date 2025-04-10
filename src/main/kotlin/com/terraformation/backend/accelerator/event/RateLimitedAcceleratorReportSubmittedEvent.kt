package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import java.time.Duration

/** Rate-limited event for sending notifications when reports are submitted. */
data class RateLimitedAcceleratorReportSubmittedEvent(val reportId: ReportId) :
    RateLimitedEvent<RateLimitedAcceleratorReportSubmittedEvent> {
  override fun getRateLimitKey() = this

  override fun getMinimumInterval(): Duration = Duration.ofMinutes(5)
}
