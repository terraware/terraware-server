package com.terraformation.backend.customer.event

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.UserId

data class FacilityAlertRequestedEvent(
    val facilityId: FacilityId,
    val subject: String,
    val body: String,
    val requestedBy: UserId,
)
