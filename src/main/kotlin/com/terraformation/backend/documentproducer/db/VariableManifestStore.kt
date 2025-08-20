package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.embeddables.pojos.VariableManifestEntryId
import com.terraformation.backend.db.docprod.tables.daos.DocumentTemplatesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableManifestEntriesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableManifestsDao
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestEntriesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestsRow
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_MANIFESTS
import com.terraformation.backend.documentproducer.model.ExistingVariableManifestModel
import com.terraformation.backend.documentproducer.model.NewVariableManifestModel
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class VariableManifestStore(
    private val clock: InstantSource,
    private val documentTemplatesDao: DocumentTemplatesDao,
    private val dslContext: DSLContext,
    private val variableManifestsDao: VariableManifestsDao,
    private val variableManifestEntriesDao: VariableManifestEntriesDao,
) {
  private val log = perClassLogger()

  /**
   * Returns the most recent variable manifest ID for each document template. Document templates
   * that don't have any variable manifests are not included.
   */
  fun fetchLatestVariableManifestIds(): Map<DocumentTemplateId, VariableManifestId> =
      dslContext
          .select(VARIABLE_MANIFESTS.DOCUMENT_TEMPLATE_ID, DSL.max(VARIABLE_MANIFESTS.ID))
          .from(VARIABLE_MANIFESTS)
          .groupBy(VARIABLE_MANIFESTS.DOCUMENT_TEMPLATE_ID)
          .fetchMap(
              VARIABLE_MANIFESTS.DOCUMENT_TEMPLATE_ID.asNonNullable(),
              DSL.max(VARIABLE_MANIFESTS.ID).asNonNullable(),
          )

  fun fetchVariableManifestByDocumentTemplate(
      documentTemplateId: DocumentTemplateId
  ): VariableManifestsRow? =
      dslContext
          .selectFrom(VARIABLE_MANIFESTS)
          .where(VARIABLE_MANIFESTS.DOCUMENT_TEMPLATE_ID.eq(documentTemplateId))
          .orderBy(VARIABLE_MANIFESTS.ID.desc())
          .limit(1)
          .fetchOneInto(VariableManifestsRow::class.java)

  fun create(newVariableManifestModel: NewVariableManifestModel): ExistingVariableManifestModel {
    requirePermissions { createVariableManifest() }

    if (!documentTemplatesDao.existsById(newVariableManifestModel.documentTemplateId)) {
      throw IllegalArgumentException(
          "Document Template ${newVariableManifestModel.documentTemplateId} does not exist"
      )
    }

    val currentUserId = currentUser().userId
    val now = clock.instant()

    val row =
        VariableManifestsRow(
            createdBy = currentUserId,
            createdTime = now,
            documentTemplateId = newVariableManifestModel.documentTemplateId,
        )

    variableManifestsDao.insert(row)

    return ExistingVariableManifestModel(row)
  }

  fun addVariableToManifestEntries(
      variableManifestEntriesRow: VariableManifestEntriesRow
  ): VariableManifestEntryId {
    variableManifestEntriesDao.insert(variableManifestEntriesRow)
    return variableManifestEntriesRow.variableManifestEntryId
  }
}
