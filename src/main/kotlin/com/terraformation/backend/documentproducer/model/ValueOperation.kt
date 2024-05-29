package com.terraformation.pdd.variable.model

import com.terraformation.pdd.jooq.DocumentId
import com.terraformation.pdd.jooq.VariableId
import com.terraformation.pdd.jooq.VariableValueId

sealed interface ValueOperation {
  val documentId: DocumentId
}

data class AppendValueOperation(
    val value: VariableValue<Nothing?, *>,
) : ValueOperation {
  override val documentId: DocumentId
    get() = value.documentId
}

data class DeleteValueOperation(
    override val documentId: DocumentId,
    val valueId: VariableValueId,
) : ValueOperation

data class ReplaceValuesOperation(
    override val documentId: DocumentId,
    val variableId: VariableId,
    val rowValueId: VariableValueId?,
    val values: List<VariableValue<Nothing?, *>>,
) : ValueOperation

data class UpdateValueOperation(
    val value: VariableValue<VariableValueId, *>,
) : ValueOperation {
  override val documentId: DocumentId
    get() = value.documentId
}
