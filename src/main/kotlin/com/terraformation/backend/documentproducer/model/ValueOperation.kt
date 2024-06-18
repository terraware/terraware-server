package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId

sealed interface ValueOperation {
  val projectId: ProjectId
}

data class AppendValueOperation(
    val value: VariableValue<Nothing?, *>,
) : ValueOperation {
  override val projectId: ProjectId
    get() = value.projectId
}

data class DeleteValueOperation(
    override val projectId: ProjectId,
    val valueId: VariableValueId,
) : ValueOperation

data class ReplaceValuesOperation(
    override val projectId: ProjectId,
    val variableId: VariableId,
    val rowValueId: VariableValueId?,
    val values: List<VariableValue<Nothing?, *>>,
) : ValueOperation

data class UpdateValueOperation(
    val value: VariableValue<VariableValueId, *>,
) : ValueOperation {
  override val projectId: ProjectId
    get() = value.projectId
}
