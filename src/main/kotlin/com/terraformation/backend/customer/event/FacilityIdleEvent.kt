package com.terraformation.backend.customer.event

import com.terraformation.backend.db.FacilityId

data class FacilityIdleEvent(val facilityId: FacilityId)
