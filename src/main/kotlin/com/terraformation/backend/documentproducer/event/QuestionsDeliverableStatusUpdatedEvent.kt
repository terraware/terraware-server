package com.terraformation.backend.documentproducer.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a questionnaire deliverable status is updated, for notifying users */
data class QuestionsDeliverableStatusUpdatedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
)
