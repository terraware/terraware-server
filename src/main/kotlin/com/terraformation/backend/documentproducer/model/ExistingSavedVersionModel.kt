package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.pojos.DocumentSavedVersionsRow
import java.time.Instant

data class ExistingSavedVersionModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val documentId: DocumentId,
    val id: DocumentSavedVersionId,
    val isSubmitted: Boolean,
    val maxVariableValueId: VariableValueId,
    val name: String,
    val variableManifestId: VariableManifestId,
) {
  constructor(
      row: DocumentSavedVersionsRow
  ) : this(
      row.createdBy!!,
      row.createdTime!!,
      row.documentId!!,
      row.id!!,
      row.isSubmitted!!,
      row.maxVariableValueId!!,
      row.name!!,
      row.variableManifestId!!,
  )
}
