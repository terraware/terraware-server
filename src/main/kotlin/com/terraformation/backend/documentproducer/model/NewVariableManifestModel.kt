package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.docprod.DocumentTemplateId

data class NewVariableManifestModel(
    val documentTemplateId: DocumentTemplateId,
)
