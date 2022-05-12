package com.terraformation.backend.customer.event

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.GerminationTestType

data class AccessionGerminationTestEvent(
    val accessionNumber: String,
    val accessionId: AccessionId,
    val testType: GerminationTestType
)
