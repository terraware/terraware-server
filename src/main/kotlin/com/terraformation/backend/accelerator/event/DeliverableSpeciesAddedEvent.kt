package com.terraformation.backend.accelerator.event

import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a species is added to a project with an associated species list deliverable */
data class DeliverableSpeciesAddedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val participantProjectSpecies: ExistingParticipantProjectSpeciesModel
)
