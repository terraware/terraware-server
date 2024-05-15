package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId

/** Published when an approved participant project species is edited */
data class ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
)
