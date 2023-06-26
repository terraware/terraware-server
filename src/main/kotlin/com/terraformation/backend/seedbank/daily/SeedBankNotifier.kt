package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.daily.FacilityNotifier
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import jakarta.inject.Named
import java.time.LocalDate
import org.springframework.context.ApplicationEventPublisher

@Named
class SeedBankNotifier(
    private val accessionStore: AccessionStore,
    private val eventPublisher: ApplicationEventPublisher,
) : FacilityNotifier {
  private val log = perClassLogger()

  override fun sendNotifications(facility: FacilityModel, todayAtFacility: LocalDate) {
    if (facility.type == FacilityType.SeedBank) {
      log.info("Generating accession notifications for facility ${facility.id}")

      if (facility.lastNotificationDate != null) {
        endDrying(facility.id, facility.lastNotificationDate, todayAtFacility)
      }
    }
  }

  private fun endDrying(facilityId: FacilityId, after: LocalDate, until: LocalDate) {
    accessionStore.fetchDryingEndDue(facilityId, after, until).forEach { (number, id) ->
      eventPublisher.publishEvent(AccessionDryingEndEvent(number, id))
    }
  }
}
