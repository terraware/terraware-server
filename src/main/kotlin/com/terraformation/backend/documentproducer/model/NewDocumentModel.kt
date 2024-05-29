package com.terraformation.pdd.document.model

import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.UserId

data class NewDocumentModel(
    val methodologyId: MethodologyId,
    val name: String,
    val organizationName: String,
    val ownedBy: UserId,
)
