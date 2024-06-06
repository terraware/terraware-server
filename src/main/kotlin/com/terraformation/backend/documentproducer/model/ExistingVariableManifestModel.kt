package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestsRow
import java.time.Instant

data class ExistingVariableManifestModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val id: VariableManifestId,
    val documentTemplateId: DocumentTemplateId,
) {
  constructor(
      row: VariableManifestsRow,
  ) : this(
      createdBy = row.createdBy!!,
      createdTime = row.createdTime!!,
      id = row.id!!,
      documentTemplateId = row.documentTemplateId!!,
  )
}
