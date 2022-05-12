package com.terraformation.backend.customer.event

import com.terraformation.backend.db.AccessionId

data class AccessionDryingStartEvent(val accessionNumber: String, val accessionId: AccessionId)
