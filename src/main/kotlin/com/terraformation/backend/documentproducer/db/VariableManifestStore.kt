package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.docprod.MethodologyId
import com.terraformation.backend.db.docprod.embeddables.pojos.VariableManifestEntryId
import com.terraformation.backend.db.docprod.tables.daos.MethodologiesDao
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

@Named
class VariableManifestStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val methodologiesDao: MethodologiesDao,
    private val variableManifestsDao: VariableManifestsDao,
    private val variableManifestEntriesDao: VariableManifestEntriesDao
) {
  private val log = perClassLogger()

  fun fetchVariableManifestByMethodology(methodologyId: MethodologyId): VariableManifestsRow? =
      dslContext
          .selectFrom(VARIABLE_MANIFESTS)
          .where(VARIABLE_MANIFESTS.METHODOLOGY_ID.eq(methodologyId))
          .orderBy(VARIABLE_MANIFESTS.ID.desc())
          .limit(1)
          .fetchOneInto(VariableManifestsRow::class.java)

  fun create(newVariableManifestModel: NewVariableManifestModel): ExistingVariableManifestModel {
    requirePermissions { createVariableManifest() }

    if (!methodologiesDao.existsById(newVariableManifestModel.methodologyId)) {
      throw IllegalArgumentException(
          "Methodology ${newVariableManifestModel.methodologyId} does not exist")
    }

    val currentUserId = currentUser().userId
    val now = clock.instant()

    val row =
        VariableManifestsRow(
            createdBy = currentUserId,
            createdTime = now,
            methodologyId = newVariableManifestModel.methodologyId,
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
