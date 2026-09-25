package com.terraformation.backend.tracking

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.event.SurvivalRateIncludesTempPlotsChangedEvent
import org.junit.jupiter.api.Test

class T0NotifierTest {
  private val rateLimitedEventPublisher = TestEventPublisher()

  private val notifier = T0Notifier(rateLimitedEventPublisher)

  @Test
  fun `publishes RateLimitedT0DataAssignedEvent when survival rate temp plots setting changes`() {
    val event =
        SurvivalRateIncludesTempPlotsChangedEvent(
            organizationId = OrganizationId(1),
            plantingSiteId = PlantingSiteId(2),
            previousValue = false,
            newValue = true,
        )

    notifier.on(event)

    rateLimitedEventPublisher.assertEventPublished(
        RateLimitedT0DataAssignedEvent(
            organizationId = OrganizationId(1),
            plantingSiteId = PlantingSiteId(2),
            previousSiteTempSetting = false,
            newSiteTempSetting = true,
        )
    )
    assertIsEventListener<SurvivalRateIncludesTempPlotsChangedEvent>(notifier)
  }
}
