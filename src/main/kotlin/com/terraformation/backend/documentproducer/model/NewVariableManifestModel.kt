package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.docprod.MethodologyId

data class NewVariableManifestModel(
    val methodologyId: MethodologyId,
)
