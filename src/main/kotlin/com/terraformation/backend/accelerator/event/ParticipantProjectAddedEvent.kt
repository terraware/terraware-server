package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.default_schema.ParticipantId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

/** Published when a project is added to a participant. */
data class ParticipantProjectAddedEvent(
    val addedBy: UserId,
    val participantId: ParticipantId,
    val projectId: ProjectId,
)
