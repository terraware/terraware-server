package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import java.time.Instant
import org.jooq.Record

data class ExistingDocumentModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val documentTemplateId: DocumentTemplateId,
    val id: DocumentId,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val name: String,
    val ownedBy: UserId,
    val projectId: ProjectId,
    val projectName: String? = null,
    val status: DocumentStatus,
    val variableManifestId: VariableManifestId,
) {
  constructor(
      row: DocumentsRow,
  ) : this(
      createdBy = row.createdBy!!,
      createdTime = row.createdTime!!,
      documentTemplateId = row.documentTemplateId!!,
      id = row.id!!,
      modifiedBy = row.modifiedBy!!,
      modifiedTime = row.modifiedTime!!,
      name = row.name!!,
      ownedBy = row.ownedBy!!,
      projectId = row.projectId!!,
      projectName = null,
      status = row.statusId!!,
      variableManifestId = row.variableManifestId!!,
  )

  companion object {
    fun of(record: Record): ExistingDocumentModel {
      return ExistingDocumentModel(
          createdBy = record[DOCUMENTS.CREATED_BY]!!,
          createdTime = record[DOCUMENTS.CREATED_TIME]!!,
          documentTemplateId = record[DOCUMENTS.DOCUMENT_TEMPLATE_ID]!!,
          id = record[DOCUMENTS.ID]!!,
          modifiedBy = record[DOCUMENTS.MODIFIED_BY]!!,
          modifiedTime = record[DOCUMENTS.MODIFIED_TIME]!!,
          name = record[DOCUMENTS.NAME]!!,
          ownedBy = record[DOCUMENTS.OWNED_BY]!!,
          projectId = record[DOCUMENTS.PROJECT_ID]!!,
          projectName = record[PROJECTS.NAME]!!,
          status = record[DOCUMENTS.STATUS_ID]!!,
          variableManifestId = record[DOCUMENTS.VARIABLE_MANIFEST_ID]!!,
      )
    }
  }
}
