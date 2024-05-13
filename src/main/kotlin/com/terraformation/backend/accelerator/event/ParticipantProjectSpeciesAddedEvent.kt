package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import java.time.Instant

/** Published when a participant project species is added */
data class ParticipantProjectSpeciesAddedEvent(
    val deliverableId: DeliverableId,
    val modifiedTime: Instant,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
)
