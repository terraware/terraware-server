package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.FacilityId

data class AccessionsAwaitingProcessingEvent(
    val facilityId: FacilityId,
    val numAccessions: Int,
    val state: AccessionState
)
