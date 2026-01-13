package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

/** Published when a project's cohort association is changed. */
data class CohortProjectAddedEvent(
    val addedBy: UserId,
    val cohortId: CohortId,
    val projectId: ProjectId,
)
