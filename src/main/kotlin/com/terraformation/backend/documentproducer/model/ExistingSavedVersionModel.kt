package com.terraformation.pdd.document.model

import com.terraformation.pdd.jooq.DocumentId
import com.terraformation.pdd.jooq.DocumentSavedVersionId
import com.terraformation.pdd.jooq.UserId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.VariableValueId
import com.terraformation.pdd.jooq.tables.pojos.DocumentSavedVersionsRow
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
