package com.terraformation.backend.tracking

import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.tracking.db.PlantingSiteStore
import javax.inject.Named
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class PlantingSiteService(
    private val eventPublisher: ApplicationEventPublisher,
    private val plantingSiteStore: PlantingSiteStore,
) {
  /**
   * Publishes [PlantingSiteTimeZoneChangedEvent]s for any planting sites whose time zones have
   * changed implicitly thanks to a change of their owning organization's time zone.
   */
  @EventListener
  fun on(event: OrganizationTimeZoneChangedEvent) {
    plantingSiteStore
        .fetchSitesByOrganizationId(event.organizationId)
        .filter { it.timeZone == null }
        .forEach { site -> eventPublisher.publishEvent(PlantingSiteTimeZoneChangedEvent(site)) }
  }
}
