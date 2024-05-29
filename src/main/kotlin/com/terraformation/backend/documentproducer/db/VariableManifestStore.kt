package com.terraformation.pdd.variable.db

import com.terraformation.pdd.api.currentUser
import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.embeddables.pojos.VariableManifestEntryId
import com.terraformation.pdd.jooq.tables.daos.MethodologiesDao
import com.terraformation.pdd.jooq.tables.daos.VariableManifestEntriesDao
import com.terraformation.pdd.jooq.tables.daos.VariableManifestsDao
import com.terraformation.pdd.jooq.tables.pojos.VariableManifestEntriesRow
import com.terraformation.pdd.jooq.tables.pojos.VariableManifestsRow
import com.terraformation.pdd.jooq.tables.references.VARIABLE_MANIFESTS
import com.terraformation.pdd.log.perClassLogger
import com.terraformation.pdd.user.PermissionChecks
import com.terraformation.pdd.variable.model.ExistingVariableManifestModel
import com.terraformation.pdd.variable.model.NewVariableManifestModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class VariableManifestStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val methodologiesDao: MethodologiesDao,
    private val permissionChecks: PermissionChecks,
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
    permissionChecks { createVariableManifest() }

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
