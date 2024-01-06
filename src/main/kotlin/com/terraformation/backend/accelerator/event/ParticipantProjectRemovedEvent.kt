package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.default_schema.ParticipantId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

/** Published when a project is removed from a participant. */
data class ParticipantProjectRemovedEvent(
    val participantId: ParticipantId,
    val projectId: ProjectId,
    val removedBy: UserId,
)
