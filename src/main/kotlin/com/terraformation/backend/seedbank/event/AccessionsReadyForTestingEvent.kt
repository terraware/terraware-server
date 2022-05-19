package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.FacilityId

data class AccessionsReadyForTestingEvent(
    val facilityId: FacilityId,
    val numAccessions: Int,
    val weeks: Int,
    val state: AccessionState
)
