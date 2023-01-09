package com.terraformation.backend.nursery.daily

import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.daily.FacilityNotifier
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.db.BatchStore
import java.time.LocalDate
import javax.inject.Named
import org.springframework.context.ApplicationEventPublisher

@Named
class NurseryNotifier(
    private val batchStore: BatchStore,
    private val eventPublisher: ApplicationEventPublisher,
) : FacilityNotifier {
  private val log = perClassLogger()

  override fun sendNotifications(facility: FacilityModel, todayAtFacility: LocalDate) {
    if (facility.type == FacilityType.Nursery) {
      log.info("Generating seedling batch notifications for facility ${facility.id}")

      if (facility.lastNotificationDate != null) {
        seedlingBatchReady(facility.id, facility.lastNotificationDate, todayAtFacility)
      }
    }
  }

  private fun seedlingBatchReady(facilityId: FacilityId, after: LocalDate, until: LocalDate) {
    batchStore.fetchEstimatedReady(facilityId, after, until).forEach { event ->
      eventPublisher.publishEvent(event)
    }
  }
}
