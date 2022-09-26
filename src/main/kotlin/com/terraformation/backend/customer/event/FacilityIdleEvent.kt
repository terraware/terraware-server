package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.FacilityId

data class FacilityIdleEvent(val facilityId: FacilityId)
