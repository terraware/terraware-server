package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
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
    val projectName: String,
    val status: DocumentStatus,
    val variableManifestId: VariableManifestId,
) {
  companion object {
    fun of(record: Record, projectName: String): ExistingDocumentModel {
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
          projectName = projectName,
          status = record[DOCUMENTS.STATUS_ID]!!,
          variableManifestId = record[DOCUMENTS.VARIABLE_MANIFEST_ID]!!,
      )
    }
  }
}
