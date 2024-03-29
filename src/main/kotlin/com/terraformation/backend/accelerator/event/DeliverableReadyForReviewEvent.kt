package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a deliverable is ready for review */
data class DeliverableReadyForReviewEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
)
