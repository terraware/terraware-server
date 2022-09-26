package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.seedbank.AccessionId

data class AccessionDryingEndEvent(val accessionNumber: String, val accessionId: AccessionId)
