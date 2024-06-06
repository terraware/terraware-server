package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import java.time.Instant

data class ExistingDocumentModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val id: DocumentId,
    val documentTemplateId: DocumentTemplateId,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val name: String,
    val organizationName: String,
    val ownedBy: UserId,
    val status: DocumentStatus,
    val variableManifestId: VariableManifestId,
) {
  constructor(
      row: DocumentsRow,
  ) : this(
      createdBy = row.createdBy!!,
      createdTime = row.createdTime!!,
      id = row.id!!,
      documentTemplateId = row.documentTemplateId!!,
      modifiedBy = row.modifiedBy!!,
      modifiedTime = row.modifiedTime!!,
      name = row.name!!,
      organizationName = row.organizationName!!,
      ownedBy = row.ownedBy!!,
      status = row.statusId!!,
      variableManifestId = row.variableManifestId!!,
  )
}
