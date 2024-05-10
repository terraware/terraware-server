package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a participant project species is submitted */
data class ParticipantProjectSpeciesSubmittedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId
)
