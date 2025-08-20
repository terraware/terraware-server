package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.daos.DocumentSavedVersionsDao
import com.terraformation.backend.db.docprod.tables.daos.DocumentTemplatesDao
import com.terraformation.backend.db.docprod.tables.daos.DocumentsDao
import com.terraformation.backend.db.docprod.tables.pojos.DocumentSavedVersionsRow
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import com.terraformation.backend.db.docprod.tables.references.DOCUMENT_SAVED_VERSIONS
import com.terraformation.backend.db.docprod.tables.references.DOCUMENT_TEMPLATES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_MANIFESTS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.documentproducer.model.EditHistoryModel
import com.terraformation.backend.documentproducer.model.ExistingDocumentModel
import com.terraformation.backend.documentproducer.model.ExistingSavedVersionModel
import com.terraformation.backend.documentproducer.model.NewDocumentModel
import com.terraformation.backend.documentproducer.model.NewSavedVersionModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

@Named
class DocumentStore(
    private val clock: InstantSource,
    private val documentSavedVersionsDao: DocumentSavedVersionsDao,
    private val documentsDao: DocumentsDao,
    private val dslContext: DSLContext,
    private val documentTemplatesDao: DocumentTemplatesDao,
) {
  fun create(newDocumentModel: NewDocumentModel): ExistingDocumentModel {
    requirePermissions { createDocument() }

    if (!documentTemplatesDao.existsById(newDocumentModel.documentTemplateId)) {
      throw IllegalArgumentException(
          "Document Template ${newDocumentModel.documentTemplateId} does not exist"
      )
    }

    val currentUserId = currentUser().userId
    val manifestId = getCurrentManifestId(newDocumentModel.documentTemplateId)
    val now = clock.instant()

    val row =
        DocumentsRow(
            createdBy = currentUserId,
            createdTime = now,
            documentTemplateId = newDocumentModel.documentTemplateId,
            modifiedBy = currentUserId,
            modifiedTime = now,
            name = newDocumentModel.name,
            ownedBy = newDocumentModel.ownedBy,
            projectId = newDocumentModel.projectId,
            statusId = DocumentStatus.Draft,
            variableManifestId = manifestId,
        )

    documentsDao.insert(row)

    return fetchOneById(row.id!!)
  }

  fun createSavedVersion(model: NewSavedVersionModel): ExistingSavedVersionModel {
    requirePermissions { createSavedVersion(model.documentId) }

    val documentsRow = fetchDocumentById(model.documentId)

    val projectMaxVariableValueId =
        dslContext
            .select(DSL.max(VARIABLE_VALUES.ID))
            .from(VARIABLE_VALUES)
            .join(DOCUMENTS)
            .on(VARIABLE_VALUES.PROJECT_ID.eq(DOCUMENTS.PROJECT_ID))
            .where(DOCUMENTS.ID.eq(model.documentId))
            .fetchOne(DSL.max(VARIABLE_VALUES.ID))
            ?: throw CannotSaveEmptyDocumentException(model.documentId)

    val versionsRow =
        DocumentSavedVersionsRow(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            documentId = model.documentId,
            isSubmitted = model.isSubmitted,
            maxVariableValueId = projectMaxVariableValueId,
            name = model.name,
            variableManifestId = documentsRow.variableManifestId,
        )

    documentSavedVersionsDao.insert(versionsRow)

    return ExistingSavedVersionModel(versionsRow)
  }

  fun fetchAll(): List<ExistingDocumentModel> = fetchByCondition()

  fun fetchByProjectId(projectId: ProjectId): List<ExistingDocumentModel> =
      fetchByCondition(DOCUMENTS.PROJECT_ID.eq(projectId))

  fun fetchOneById(documentId: DocumentId): ExistingDocumentModel =
      fetchByCondition(DOCUMENTS.ID.eq(documentId)).firstOrNull()
          ?: throw DocumentNotFoundException(documentId)

  fun fetchDocumentById(documentId: DocumentId): DocumentsRow {
    requirePermissions { readDocument(documentId) }

    return documentsDao.fetchOneById(documentId) ?: throw DocumentNotFoundException(documentId)
  }

  fun fetchSavedVersion(
      documentId: DocumentId,
      versionId: DocumentSavedVersionId,
  ): ExistingSavedVersionModel {
    requirePermissions { readDocument(documentId) }

    val versionsRow =
        documentSavedVersionsDao.fetchOneById(versionId)
            ?: throw SavedVersionNotFoundException(documentId, versionId)
    if (versionsRow.documentId != documentId) {
      throw SavedVersionNotFoundException(documentId, versionId)
    }

    return ExistingSavedVersionModel(versionsRow)
  }

  /** Returns a list of saved versions in reverse chronological order. */
  fun listSavedVersions(documentId: DocumentId): List<ExistingSavedVersionModel> {
    // Check for permission and document existence.
    fetchDocumentById(documentId)

    return dslContext
        .selectFrom(DOCUMENT_SAVED_VERSIONS)
        .where(DOCUMENT_SAVED_VERSIONS.DOCUMENT_ID.eq(documentId))
        .orderBy(DOCUMENT_SAVED_VERSIONS.CREATED_TIME.desc())
        .fetchInto(DocumentSavedVersionsRow::class.java)
        .map { ExistingSavedVersionModel(it) }
  }

  /**
   * Returns an abbreviated history of document edits. An "edit" is a new variable value. Edits by
   * the same user are collapsed into one history entry if they fall on the same UTC date and if
   * there isn't a saved version in between them.
   */
  fun listEditHistory(documentId: DocumentId): List<EditHistoryModel> {
    // Check for permission and document existence.
    fetchDocumentById(documentId)

    // We'll group the results by the ID of the first saved version after the edit (if any) and by
    // the UTC date of the edit. The "first saved version after the edit" logic needs to use a
    // lateral join such that it's evaluated for each row of variable_values.
    val subquery =
        DSL.lateral(
            DSL.select(DSL.min(DOCUMENT_SAVED_VERSIONS.ID))
                .from(DOCUMENT_SAVED_VERSIONS)
                .where(DOCUMENT_SAVED_VERSIONS.DOCUMENT_ID.eq(documentId))
                .and(DOCUMENT_SAVED_VERSIONS.MAX_VARIABLE_VALUE_ID.ge(VARIABLE_VALUES.ID))
        )
    val nextSavedVersionIdField = subquery.field(DSL.min(DOCUMENT_SAVED_VERSIONS.ID))

    // jOOQ doesn't have built-in support for PostgreSQL's AT TIME ZONE, so use a SQL template.
    val createdDateField =
        DSL.field(
            "({0} AT TIME ZONE 'UTC')::DATE",
            SQLDataType.LOCALDATE,
            VARIABLE_VALUES.CREATED_TIME,
        )

    return dslContext
        .select(
            VARIABLE_VALUES.CREATED_BY.asNonNullable(),
            DSL.max(VARIABLE_VALUES.CREATED_TIME).asNonNullable(),
        )
        .from(VARIABLE_VALUES, DOCUMENTS, subquery)
        .where(DOCUMENTS.ID.eq(documentId))
        .and(DOCUMENTS.PROJECT_ID.eq(VARIABLE_VALUES.PROJECT_ID))
        .groupBy(nextSavedVersionIdField, createdDateField, VARIABLE_VALUES.CREATED_BY)
        .orderBy(
            DSL.max(VARIABLE_VALUES.CREATED_TIME).desc().nullsFirst(),
            VARIABLE_VALUES.CREATED_BY,
        )
        .fetch { record -> EditHistoryModel(record.value1(), record.value2()) }
  }

  fun updateDocument(
      documentId: DocumentId,
      applyChanges: (DocumentsRow) -> DocumentsRow,
  ): DocumentsRow {
    requirePermissions { updateDocument(documentId) }

    val documentsRow = fetchDocumentById(documentId)

    val updatedRow =
        applyChanges(documentsRow)
            .copy(
                id = documentId,
                modifiedBy = currentUser().userId,
                modifiedTime = clock.instant(),
            )

    documentsDao.update(updatedRow)

    return updatedRow
  }

  fun updateSavedVersion(
      documentId: DocumentId,
      versionId: DocumentSavedVersionId,
      applyChanges: (DocumentSavedVersionsRow) -> DocumentSavedVersionsRow,
  ): DocumentSavedVersionsRow {
    requirePermissions { updateDocument(documentId) }

    val originalRow =
        documentSavedVersionsDao.fetchOneById(versionId)
            ?: throw SavedVersionNotFoundException(documentId, versionId)
    if (originalRow.documentId != documentId) {
      throw SavedVersionNotFoundException(documentId, versionId)
    }

    val updatedRow = applyChanges(originalRow).copy(id = versionId, documentId = documentId)

    documentSavedVersionsDao.update(updatedRow)

    return updatedRow
  }

  private fun getCurrentManifestId(documentTemplateId: DocumentTemplateId): VariableManifestId {
    return dslContext
        .select(VARIABLE_MANIFESTS.ID)
        .from(VARIABLE_MANIFESTS)
        .where(VARIABLE_MANIFESTS.DOCUMENT_TEMPLATE_ID.eq(documentTemplateId))
        .orderBy(VARIABLE_MANIFESTS.ID.desc())
        .limit(1)
        .fetchOne(VARIABLE_MANIFESTS.ID)
        ?: throw MissingVariableManifestException(documentTemplateId)
  }

  private fun fetchByCondition(condition: Condition? = null): List<ExistingDocumentModel> {
    val lastSavedVersionIdField =
        with(DOCUMENT_SAVED_VERSIONS) {
          DSL.field(
              DSL.select(DSL.max(ID))
                  .from(DOCUMENT_SAVED_VERSIONS)
                  .where(DOCUMENT_ID.eq(DOCUMENTS.ID))
          )
        }

    return dslContext
        .select(
            DOCUMENTS.asterisk(),
            DOCUMENT_TEMPLATES.NAME,
            PROJECTS.NAME,
            PROJECT_ACCELERATOR_DETAILS.DEAL_NAME,
            lastSavedVersionIdField,
        )
        .from(DOCUMENTS)
        .join(DOCUMENT_TEMPLATES)
        .on(DOCUMENTS.DOCUMENT_TEMPLATE_ID.eq(DOCUMENT_TEMPLATES.ID))
        .join(PROJECTS)
        .on(DOCUMENTS.PROJECT_ID.eq(PROJECTS.ID))
        .leftJoin(PROJECT_ACCELERATOR_DETAILS)
        .on(PROJECTS.ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
        .where(condition)
        .fetch()
        .filter { currentUser().canReadDocument(it[DOCUMENTS.ID]!!) }
        .map { ExistingDocumentModel.of(it, lastSavedVersionIdField) }
  }
}
