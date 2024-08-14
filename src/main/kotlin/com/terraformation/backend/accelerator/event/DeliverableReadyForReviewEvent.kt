package com.terraformation.backend.accelerator.event

import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a deliverable is ready for review */
data class DeliverableReadyForReviewEvent(
    val deliverable: DeliverableSubmissionModel,
    val projectId: ProjectId,
)
