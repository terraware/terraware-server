package com.terraformation.backend.documentproducer.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a questions deliverable for a project was updated * */
data class QuestionsDeliverableSubmittedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
)
