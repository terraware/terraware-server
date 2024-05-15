package com.terraformation.backend.accelerator.event

import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.db.accelerator.DeliverableId

/** Published when a participant project species is added */
data class ParticipantProjectSpeciesAddedEvent(
    val deliverableId: DeliverableId,
    val participantProjectSpecies: ExistingParticipantProjectSpeciesModel,
)
