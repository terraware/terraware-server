package com.terraformation.backend.daily

import com.terraformation.backend.customer.model.FacilityModel
import java.time.LocalDate

fun interface FacilityNotifier {
  fun sendNotifications(facility: FacilityModel, todayAtFacility: LocalDate)
}
