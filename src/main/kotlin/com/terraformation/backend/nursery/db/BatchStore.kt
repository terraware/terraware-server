package com.terraformation.backend.nursery.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.InventoriesRow
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.nursery.model.SpeciesSummary
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class BatchStore(
    private val batchesDao: BatchesDao,
    private val batchQuantityHistoryDao: BatchQuantityHistoryDao,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val identifierGenerator: IdentifierGenerator,
    private val parentStore: ParentStore,
) {
  fun fetchOneById(batchId: BatchId): BatchesRow {
    requirePermissions { readBatch(batchId) }

    return batchesDao.fetchOneById(batchId) ?: throw BatchNotFoundException(batchId)
  }

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

  fun getSpeciesSummary(speciesId: SpeciesId): SpeciesSummary {
    requirePermissions { readSpecies(speciesId) }

    val inventory =
        dslContext
            .selectFrom(INVENTORIES)
            .where(INVENTORIES.SPECIES_ID.eq(speciesId))
            .fetchOneInto(InventoriesRow::class.java)

    val totalWithdrawnByPurpose =
        dslContext
            .select(
                WITHDRAWALS.PURPOSE_ID,
                DSL.sum(
                    BATCH_WITHDRAWALS.NOT_READY_QUANTITY_WITHDRAWN.plus(
                        BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN)))
            .from(BATCHES)
            .join(BATCH_WITHDRAWALS)
            .on(BATCHES.ID.eq(BATCH_WITHDRAWALS.BATCH_ID))
            .join(WITHDRAWALS)
            .on(BATCH_WITHDRAWALS.WITHDRAWAL_ID.eq(WITHDRAWALS.ID))
            .where(BATCHES.SPECIES_ID.eq(speciesId))
            .groupBy(WITHDRAWALS.PURPOSE_ID)
            .fetch()

    val totalWithdrawn = totalWithdrawnByPurpose.sumOf { it.value2().toLong() }
    val totalDead =
        totalWithdrawnByPurpose
            .filter { it.value1() == WithdrawalPurpose.Dead }
            .sumOf { it.value2().toLong() }

    val totalIncludingWithdrawn = totalWithdrawn + (inventory?.totalQuantity ?: 0)
    val lossRate =
        if (totalIncludingWithdrawn > 0) {
          // Round to nearest integer percentage.
          (totalDead * 100 + totalIncludingWithdrawn / 2) / totalIncludingWithdrawn
        } else {
          0
        }

    val nurseries =
        dslContext
            .selectDistinct(FACILITIES.ID, FACILITIES.NAME)
            .from(BATCHES)
            .join(FACILITIES)
            .on(BATCHES.FACILITY_ID.eq(FACILITIES.ID))
            .where(BATCHES.SPECIES_ID.eq(speciesId))
            .orderBy(FACILITIES.NAME)
            .fetchInto(FacilitiesRow::class.java)

    return SpeciesSummary(
        germinatingQuantity = inventory?.germinatingQuantity ?: 0,
        lossRate = lossRate.toInt(),
        notReadyQuantity = inventory?.notReadyQuantity ?: 0,
        nurseries = nurseries,
        readyQuantity = inventory?.readyQuantity ?: 0,
        speciesId = speciesId,
        totalDead = totalDead,
        totalQuantity = inventory?.totalQuantity ?: 0,
        totalWithdrawn = totalWithdrawn,
    )
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
