package com.terraformation.backend.accelerator.event

import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a species associated to a project's species list deliverable is edited */
data class ParticipantProjectSpeciesEditedEvent(
    val oldParticipantProjectSpecies: ExistingParticipantProjectSpeciesModel,
    val newParticipantProjectSpecies: ExistingParticipantProjectSpeciesModel,
    val projectId: ProjectId,
)
