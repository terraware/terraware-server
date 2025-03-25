package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.docprod.VariableWorkflowStatus

data class VariableWorkflowModel(
    val status: VariableWorkflowStatus,
    val feedback: String?,
    val internalComment: String?,
)
