package com.terraformation.backend.nursery.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class BatchStore(
    private val batchesDao: BatchesDao,
    private val batchQuantityHistoryDao: BatchQuantityHistoryDao,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val identifierGenerator: IdentifierGenerator,
    private val parentStore: ParentStore,
) {
  fun create(row: BatchesRow): BatchesRow {
    val facilityId =
        row.facilityId ?: throw IllegalArgumentException("Facility ID must be non-null")
    val facilityType = parentStore.getFacilityType(facilityId)
    val organizationId = parentStore.getOrganizationId(facilityId)
    val speciesId = row.speciesId ?: throw IllegalArgumentException("Species ID must be non-null")
    val now = clock.instant()
    val userId = currentUser().userId

    requirePermissions { createBatch(facilityId) }

    if (facilityType != FacilityType.Nursery) {
      throw FacilityTypeMismatchException(facilityId, FacilityType.Nursery)
    }

    if (organizationId != parentStore.getOrganizationId(speciesId)) {
      throw SpeciesNotFoundException(speciesId)
    }

    val rowWithDefaults =
        row.copy(
            batchNumber = identifierGenerator.generateIdentifier(),
            createdBy = userId,
            createdTime = now,
            modifiedBy = userId,
            modifiedTime = now,
            latestObservedGerminatingQuantity = row.germinatingQuantity,
            latestObservedNotReadyQuantity = row.notReadyQuantity,
            latestObservedReadyQuantity = row.readyQuantity,
            latestObservedTime = now,
            organizationId = organizationId,
            version = 1,
        )

    dslContext.transaction { _ ->
      batchesDao.insert(rowWithDefaults)
      insertQuantityHistoryRow(rowWithDefaults, BatchQuantityHistoryType.Observed)
    }

    return rowWithDefaults
  }

  private fun insertQuantityHistoryRow(row: BatchesRow, historyType: BatchQuantityHistoryType) {
    batchQuantityHistoryDao.insert(
        BatchQuantityHistoryRow(
            batchId = row.id,
            historyTypeId = historyType,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            readyQuantity = row.readyQuantity,
            notReadyQuantity = row.notReadyQuantity,
            germinatingQuantity = row.germinatingQuantity,
        ))
  }
}
