package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.default_schema.ProjectId

data class ProjectPhaseUpdatedEvent(
    val projectId: ProjectId,
    val newPhase: AcceleratorPhase?,
)
