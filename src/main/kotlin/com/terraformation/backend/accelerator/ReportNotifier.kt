package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.event.AcceleratorReportSubmittedEvent
import com.terraformation.backend.accelerator.event.RateLimitedAcceleratorReportSubmittedEvent
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class ReportNotifier(
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
) {
  /** Schedules a "ready for review" notification when a report is submitted. */
  @EventListener
  fun on(event: AcceleratorReportSubmittedEvent) {
    rateLimitedEventPublisher.publishEvent(
        RateLimitedAcceleratorReportSubmittedEvent(event.reportId)
    )
  }
}
