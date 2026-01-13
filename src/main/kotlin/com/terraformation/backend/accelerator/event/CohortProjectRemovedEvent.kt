package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

/** Published when a project is removed from a cohort. */
data class CohortProjectRemovedEvent(
    val cohortId: CohortId,
    val projectId: ProjectId,
    val removedBy: UserId,
)
