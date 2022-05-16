package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.AccessionId

data class AccessionWithdrawalEvent(val accessionNumber: String, val accessionId: AccessionId)
