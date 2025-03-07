package com.terraformation.backend.funder.event

import com.terraformation.backend.db.funder.FundingEntityId

data class FunderInvitedToFundingEntityEvent(
    val email: String,
    val fundingEntityId: FundingEntityId,
)
