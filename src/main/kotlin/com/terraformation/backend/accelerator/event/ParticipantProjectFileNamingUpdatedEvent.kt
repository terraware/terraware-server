package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.default_schema.ProjectId

data class ParticipantProjectFileNamingUpdatedEvent(val projectId: ProjectId)
