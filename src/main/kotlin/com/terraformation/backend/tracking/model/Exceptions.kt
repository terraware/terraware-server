package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId

class SubstratumFullException(
    val substratumId: SubstratumId,
    val plotsNeeded: Int,
    val plotsRemaining: Int,
) :
    IllegalStateException(
        "Substratum $substratumId needs $plotsNeeded temporary plots but only " +
            "$plotsRemaining available"
    )

class StratumFullException(val stratumId: StratumId) :
    IllegalStateException("Stratum $stratumId has no room for any monitoring plots")
