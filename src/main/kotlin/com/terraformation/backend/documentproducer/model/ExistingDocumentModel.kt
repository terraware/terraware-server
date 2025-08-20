package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import com.terraformation.backend.db.docprod.tables.references.DOCUMENT_TEMPLATES
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class ExistingDocumentModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val documentTemplateId: DocumentTemplateId,
    val documentTemplateName: String,
    val id: DocumentId,
    val internalComment: String? = null,
    val lastSavedVersionId: DocumentSavedVersionId? = null,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val name: String,
    val ownedBy: UserId,
    val projectDealName: String?,
    val projectId: ProjectId,
    val projectName: String,
    val status: DocumentStatus,
    val variableManifestId: VariableManifestId,
) {
  companion object {
    fun of(
        record: Record,
        lastSavedVersionIdField: Field<DocumentSavedVersionId?>? = null,
    ): ExistingDocumentModel {
      return ExistingDocumentModel(
          createdBy = record[DOCUMENTS.CREATED_BY]!!,
          createdTime = record[DOCUMENTS.CREATED_TIME]!!,
          documentTemplateId = record[DOCUMENTS.DOCUMENT_TEMPLATE_ID]!!,
          documentTemplateName = record[DOCUMENT_TEMPLATES.NAME]!!,
          id = record[DOCUMENTS.ID]!!,
          internalComment = record[DOCUMENTS.INTERNAL_COMMENT],
          lastSavedVersionId = lastSavedVersionIdField?.let { record[it] },
          modifiedBy = record[DOCUMENTS.MODIFIED_BY]!!,
          modifiedTime = record[DOCUMENTS.MODIFIED_TIME]!!,
          name = record[DOCUMENTS.NAME]!!,
          ownedBy = record[DOCUMENTS.OWNED_BY]!!,
          projectDealName = record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME],
          projectId = record[DOCUMENTS.PROJECT_ID]!!,
          projectName = record[PROJECTS.NAME]!!,
          status = record[DOCUMENTS.STATUS_ID]!!,
          variableManifestId = record[DOCUMENTS.VARIABLE_MANIFEST_ID]!!,
      )
    }
  }
}
