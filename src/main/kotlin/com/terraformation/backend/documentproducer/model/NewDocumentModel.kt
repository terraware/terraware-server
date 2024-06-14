package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentTemplateId

data class NewDocumentModel(
    val documentTemplateId: DocumentTemplateId,
    val name: String,
    val ownedBy: UserId,
    val projectId: ProjectId,
)
