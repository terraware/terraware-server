package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.db.docprod.VariableType

data class StableId(
    val value: String,
    val variableType: VariableType,
)
