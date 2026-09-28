package com.terraformation.backend.tracking

import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.event.SurvivalRateIncludesTempPlotsChangedEvent
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class T0Notifier(
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
) {
  /** Schedules a "T0 data changed" notification when a site's temp plots setting changes. */
  @EventListener
  fun on(event: SurvivalRateIncludesTempPlotsChangedEvent) {
    rateLimitedEventPublisher.publishEvent(
        RateLimitedT0DataAssignedEvent(
            organizationId = event.organizationId,
            plantingSiteId = event.plantingSiteId,
            previousSiteTempSetting = event.previousValue,
            newSiteTempSetting = event.newValue,
        )
    )
  }
}
