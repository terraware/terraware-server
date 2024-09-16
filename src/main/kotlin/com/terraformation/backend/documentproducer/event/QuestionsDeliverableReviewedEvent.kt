package com.terraformation.backend.documentproducer.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId

/**
 * Published when a questions deliverable for a project was reviewed and contained user-visible
 * status changes or feedback
 */
data class QuestionsDeliverableReviewedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
)
