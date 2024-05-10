package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a participant project species is edited */
data class ParticipantProjectSpeciesEditedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId
)
