package com.terraformation.pdd.document.model

import com.terraformation.pdd.jooq.DocumentId
import com.terraformation.pdd.jooq.DocumentStatus
import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.UserId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.tables.pojos.DocumentsRow
import java.time.Instant

data class ExistingDocumentModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val id: DocumentId,
    val methodologyId: MethodologyId,
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
      methodologyId = row.methodologyId!!,
      modifiedBy = row.modifiedBy!!,
      modifiedTime = row.modifiedTime!!,
      name = row.name!!,
      organizationName = row.organizationName!!,
      ownedBy = row.ownedBy!!,
      status = row.statusId!!,
      variableManifestId = row.variableManifestId!!,
  )
}
