package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId

/** Published when a variable value is updated */
data class VariableValueUpdatedEvent(
    val projectId: ProjectId,
    val variableId: VariableId,
)
