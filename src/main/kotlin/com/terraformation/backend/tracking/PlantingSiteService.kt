package com.terraformation.backend.tracking

import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.PlantingSiteInUseException
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.db.PlantingSiteStore
import jakarta.inject.Named
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class PlantingSiteService(
    private val eventPublisher: ApplicationEventPublisher,
    private val plantingSiteStore: PlantingSiteStore,
) {

  /**
   * Deletes a planting site if it has no plantings. throws [PlantingSiteInUseException] if planting
   * site has plantings
   */
  fun deletePlantingSite(plantingSiteId: PlantingSiteId) {
    requirePermissions { deletePlantingSite(plantingSiteId) }

    if (plantingSiteStore.hasPlantings(plantingSiteId)) {
      throw PlantingSiteInUseException(plantingSiteId)
    }

    plantingSiteStore.deletePlantingSite(plantingSiteId)
  }

  /**
   * Publishes [PlantingSiteTimeZoneChangedEvent]s for any planting sites whose time zones have
   * changed implicitly thanks to a change of their owning organization's time zone.
   */
  @EventListener
  fun on(event: OrganizationTimeZoneChangedEvent) {
    plantingSiteStore
        .fetchSitesByOrganizationId(event.organizationId)
        .filter { it.timeZone == null }
        .forEach { site ->
          eventPublisher.publishEvent(
              PlantingSiteTimeZoneChangedEvent(site, event.oldTimeZone, event.newTimeZone))
        }
  }
}
