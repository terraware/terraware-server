package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.StratumId

class StratumFullException(val stratumId: StratumId) :
    IllegalStateException("Stratum $stratumId has no room for any monitoring plots")
