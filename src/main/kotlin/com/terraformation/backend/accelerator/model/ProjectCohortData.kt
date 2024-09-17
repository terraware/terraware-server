package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase

data class ProjectCohortData(
    val cohortId: CohortId? = null,
    val cohortPhase: CohortPhase,
)
