package com.terraformation.backend.documentproducer.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId

/** Published when a questions deliverable for a project was updated * */
data class QuestionsDeliverableSubmittedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val valueIds: Map<VariableId, VariableValueId>,
)
