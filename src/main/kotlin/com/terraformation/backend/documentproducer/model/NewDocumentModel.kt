package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.MethodologyId

data class NewDocumentModel(
    val methodologyId: MethodologyId,
    val name: String,
    val organizationName: String,
    val ownedBy: UserId,
)
