package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ParticipantId

/** Published when a participant's cohort association is changed. */
data class CohortParticipantAddedEvent(
    val cohortId: CohortId,
    val participantId: ParticipantId,
)
