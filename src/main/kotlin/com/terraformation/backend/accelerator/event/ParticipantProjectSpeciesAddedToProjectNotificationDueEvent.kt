package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId

/** Published when a participant project species is added to a project */
data class ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
)
