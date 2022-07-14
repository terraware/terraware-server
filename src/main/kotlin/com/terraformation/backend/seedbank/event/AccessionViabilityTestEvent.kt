package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.ViabilityTestType

data class AccessionViabilityTestEvent(
    val accessionNumber: String,
    val accessionId: AccessionId,
    val testType: ViabilityTestType
)
