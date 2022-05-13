package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.AccessionId

data class AccessionDryingEndEvent(val accessionNumber: String, val accessionId: AccessionId)
