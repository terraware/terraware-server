package com.terraformation.backend.nursery.event

import com.terraformation.backend.db.nursery.WithdrawalId

/** Published when a withdrawal is about to be deleted from the database. */
data class WithdrawalDeletionStartedEvent(val withdrawalId: WithdrawalId)
