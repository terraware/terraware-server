package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.default_schema.UserId

/** Published when a participant's cohort association is changed. */
data class DefaultVoterChangedEvent(
    val userId: UserId,
)
