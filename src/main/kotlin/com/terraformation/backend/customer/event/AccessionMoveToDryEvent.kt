package com.terraformation.backend.customer.event

import com.terraformation.backend.db.AccessionId

data class AccessionMoveToDryEvent(val accessionNumber: String, val accessionId: AccessionId)
