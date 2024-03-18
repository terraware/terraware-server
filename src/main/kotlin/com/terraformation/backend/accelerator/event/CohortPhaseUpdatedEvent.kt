package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase

/** Published when a cohort moves from one phase to another. */
data class CohortPhaseUpdatedEvent(
    val cohortId: CohortId,
    val newPhase: CohortPhase,
)
