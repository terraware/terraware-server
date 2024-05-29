package com.terraformation.pdd.document.db

import com.terraformation.pdd.api.currentUser
import com.terraformation.pdd.db.asNonNullable
import com.terraformation.pdd.document.model.EditHistoryModel
import com.terraformation.pdd.document.model.ExistingDocumentModel
import com.terraformation.pdd.document.model.ExistingSavedVersionModel
import com.terraformation.pdd.document.model.NewDocumentModel
import com.terraformation.pdd.document.model.NewSavedVersionModel
import com.terraformation.pdd.jooq.DocumentId
import com.terraformation.pdd.jooq.DocumentSavedVersionId
import com.terraformation.pdd.jooq.DocumentStatus
import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.tables.daos.DocumentSavedVersionsDao
import com.terraformation.pdd.jooq.tables.daos.DocumentsDao
import com.terraformation.pdd.jooq.tables.daos.MethodologiesDao
import com.terraformation.pdd.jooq.tables.pojos.DocumentSavedVersionsRow
import com.terraformation.pdd.jooq.tables.pojos.DocumentsRow
import com.terraformation.pdd.jooq.tables.references.DOCUMENT_SAVED_VERSIONS
import com.terraformation.pdd.jooq.tables.references.VARIABLE_MANIFESTS
import com.terraformation.pdd.jooq.tables.references.VARIABLE_VALUES
import com.terraformation.pdd.user.PermissionChecks
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

@Named
class DocumentStore(
    private val clock: InstantSource,
    private val documentSavedVersionsDao: DocumentSavedVersionsDao,
    private val documentsDao: DocumentsDao,
    private val dslContext: DSLContext,
    private val methodologiesDao: MethodologiesDao,
    private val permissionChecks: PermissionChecks,
) {
  fun create(newDocumentModel: NewDocumentModel): ExistingDocumentModel {
    permissionChecks { createDocument() }

    if (!methodologiesDao.existsById(newDocumentModel.methodologyId)) {
      throw IllegalArgumentException("Methodology ${newDocumentModel.methodologyId} does not exist")
    }

    val currentUserId = currentUser().userId
    val manifestId = getCurrentManifestId(newDocumentModel.methodologyId)
    val now = clock.instant()

    val row =
        DocumentsRow(
            createdBy = currentUserId,
            createdTime = now,
            methodologyId = newDocumentModel.methodologyId,
            modifiedBy = currentUserId,
            modifiedTime = now,
            name = newDocumentModel.name,
            organizationName = newDocumentModel.organizationName,
            ownedBy = newDocumentModel.ownedBy,
            statusId = DocumentStatus.Draft,
            variableManifestId = manifestId,
        )

    documentsDao.insert(row)

    return ExistingDocumentModel(row)
  }

  fun createSavedVersion(model: NewSavedVersionModel): ExistingSavedVersionModel {
    permissionChecks { createSavedVersion(model.documentId) }

    val documentsRow = fetchDocumentById(model.documentId)

    val maxVariableValueId =
        dslContext
            .select(DSL.max(VARIABLE_VALUES.ID))
            .from(VARIABLE_VALUES)
            .where(VARIABLE_VALUES.DOCUMENT_ID.eq(model.documentId))
            .fetchOne(DSL.max(VARIABLE_VALUES.ID))
            ?: throw CannotSaveEmptyDocumentException(model.documentId)

    val versionsRow =
        DocumentSavedVersionsRow(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            documentId = model.documentId,
            isSubmitted = model.isSubmitted,
            maxVariableValueId = maxVariableValueId,
            name = model.name,
            variableManifestId = documentsRow.variableManifestId,
        )

    documentSavedVersionsDao.insert(versionsRow)

    return ExistingSavedVersionModel(versionsRow)
  }

  fun findAll(): List<ExistingDocumentModel> {
    return documentsDao.findAll().map { ExistingDocumentModel(it) }
  }

  fun fetchDocumentById(documentId: DocumentId): DocumentsRow {
    permissionChecks { readDocument(documentId) }

    return documentsDao.fetchOneById(documentId) ?: throw DocumentNotFoundException(documentId)
  }

  fun fetchSavedVersion(
      documentId: DocumentId,
      versionId: DocumentSavedVersionId
  ): ExistingSavedVersionModel {
    permissionChecks { readDocument(documentId) }

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
                .and(DOCUMENT_SAVED_VERSIONS.MAX_VARIABLE_VALUE_ID.ge(VARIABLE_VALUES.ID)))
    val nextSavedVersionIdField = subquery.field(DSL.min(DOCUMENT_SAVED_VERSIONS.ID))

    // jOOQ doesn't have built-in support for PostgreSQL's AT TIME ZONE, so use a SQL template.
    val createdDateField =
        DSL.field(
            "({0} AT TIME ZONE 'UTC')::DATE", SQLDataType.LOCALDATE, VARIABLE_VALUES.CREATED_TIME)

    return dslContext
        .select(
            VARIABLE_VALUES.CREATED_BY.asNonNullable(),
            DSL.max(VARIABLE_VALUES.CREATED_TIME).asNonNullable())
        .from(VARIABLE_VALUES, subquery)
        .where(VARIABLE_VALUES.DOCUMENT_ID.eq(documentId))
        .groupBy(nextSavedVersionIdField, createdDateField, VARIABLE_VALUES.CREATED_BY)
        .orderBy(
            DSL.max(VARIABLE_VALUES.CREATED_TIME).desc().nullsFirst(), VARIABLE_VALUES.CREATED_BY)
        .fetch { record -> EditHistoryModel(record.value1(), record.value2()) }
  }

  fun updateDocument(
      documentId: DocumentId,
      applyChanges: (DocumentsRow) -> DocumentsRow
  ): DocumentsRow {
    permissionChecks { updateDocument(documentId) }

    val documentsRow = fetchDocumentById(documentId)

    val updatedRow =
        applyChanges(documentsRow)
            .copy(
                id = documentId, modifiedBy = currentUser().userId, modifiedTime = clock.instant())

    documentsDao.update(updatedRow)

    return updatedRow
  }

  fun updateSavedVersion(
      documentId: DocumentId,
      versionId: DocumentSavedVersionId,
      applyChanges: (DocumentSavedVersionsRow) -> DocumentSavedVersionsRow
  ): DocumentSavedVersionsRow {
    permissionChecks { updateDocument(documentId) }

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

  private fun getCurrentManifestId(methodologyId: MethodologyId): VariableManifestId {
    return dslContext
        .select(VARIABLE_MANIFESTS.ID)
        .from(VARIABLE_MANIFESTS)
        .where(VARIABLE_MANIFESTS.METHODOLOGY_ID.eq(methodologyId))
        .orderBy(VARIABLE_MANIFESTS.ID.desc())
        .limit(1)
        .fetchOne(VARIABLE_MANIFESTS.ID) ?: throw MissingVariableManifestException(methodologyId)
  }
}
