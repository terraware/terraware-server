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
import com.terraformation.backend.db.nursery.tables.Batches
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.InventoriesRow
import com.terraformation.backend.db.nursery.tables.records.BatchesRecord
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.model.SpeciesSummary
import java.time.Clock
import java.time.LocalDate
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.UpdateSetFirstStep
import org.jooq.UpdateSetMoreStep
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
  private val log = perClassLogger()

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

      insertQuantityHistoryRow(
          rowWithDefaults.id!!,
          rowWithDefaults.germinatingQuantity!!,
          rowWithDefaults.notReadyQuantity!!,
          rowWithDefaults.readyQuantity!!,
          BatchQuantityHistoryType.Observed)
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

  fun updateDetails(
      batchId: BatchId,
      version: Int,
      notes: String?,
      readyByDate: LocalDate?,
  ) {
    requirePermissions { updateBatch(batchId) }

    updateVersionedBatch(batchId, version) { it.set(NOTES, notes).set(READY_BY_DATE, readyByDate) }
  }

  fun updateQuantities(
      batchId: BatchId,
      version: Int,
      germinating: Int,
      notReady: Int,
      ready: Int,
      historyType: BatchQuantityHistoryType,
  ) {
    if (germinating < 0 || notReady < 0 || ready < 0) {
      throw IllegalArgumentException("Quantities may not be negative")
    }

    requirePermissions { updateBatch(batchId) }

    dslContext.transaction { _ ->
      updateVersionedBatch(batchId, version) { update ->
        update
            .set(GERMINATING_QUANTITY, germinating)
            .set(NOT_READY_QUANTITY, notReady)
            .set(READY_QUANTITY, ready)
            .let {
              if (historyType == BatchQuantityHistoryType.Observed) {
                it.set(LATEST_OBSERVED_GERMINATING_QUANTITY, germinating)
                    .set(LATEST_OBSERVED_NOT_READY_QUANTITY, notReady)
                    .set(LATEST_OBSERVED_READY_QUANTITY, ready)
                    .set(LATEST_OBSERVED_TIME, clock.instant())
              } else {
                it
              }
            }
      }

      insertQuantityHistoryRow(batchId, germinating, notReady, ready, historyType)
    }
  }

  fun delete(batchId: BatchId) {
    requirePermissions { deleteBatch(batchId) }

    log.info("Deleting batch $batchId")

    dslContext.transaction { _ ->
      // If two threads delete batches that share a single withdrawal, the "check if there are other
      // batches on the withdrawal" logic here has a race condition. We could use "serializable"
      // transaction isolation to avoid the race, but it's awkward to set isolation levels using
      // jOOQ's API (see https://github.com/jOOQ/jOOQ/issues/4836) so instead, explicitly lock the
      // withdrawals.
      dslContext
          .selectOne()
          .from(WITHDRAWALS)
          .where(
              WITHDRAWALS.ID.`in`(
                  DSL.select(BATCH_WITHDRAWALS.WITHDRAWAL_ID)
                      .from(BATCH_WITHDRAWALS)
                      .where(BATCH_WITHDRAWALS.BATCH_ID.eq(batchId))))
          .forUpdate()
          .execute()

      // Withdrawals that are only from this batch should be deleted.
      dslContext
          .deleteFrom(WITHDRAWALS)
          .where(
              WITHDRAWALS.ID.`in`(
                  DSL.select(BATCH_WITHDRAWALS.WITHDRAWAL_ID)
                      .from(BATCH_WITHDRAWALS)
                      .where(BATCH_WITHDRAWALS.BATCH_ID.eq(batchId))))
          .andNotExists(
              DSL.selectOne()
                  .from(BATCH_WITHDRAWALS)
                  .where(WITHDRAWALS.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
                  .and(BATCH_WITHDRAWALS.BATCH_ID.ne(batchId)))
          .execute()

      // Withdrawals that are from this batch as well as other batches should be considered
      // modified because we're removing batch withdrawals (and thus changing their quantities).
      dslContext
          .update(WITHDRAWALS)
          .set(WITHDRAWALS.MODIFIED_BY, currentUser().userId)
          .set(WITHDRAWALS.MODIFIED_TIME, clock.instant())
          .where(
              WITHDRAWALS.ID.`in`(
                  DSL.select(BATCH_WITHDRAWALS.WITHDRAWAL_ID)
                      .from(BATCH_WITHDRAWALS)
                      .where(BATCH_WITHDRAWALS.BATCH_ID.eq(batchId))))
          .execute()

      // Cascading delete/update will take care of deleting child objects and clearing references.
      batchesDao.deleteById(batchId)
    }
  }

  /**
   * Applies an update to a batch if the caller-supplied version number is up to date.
   *
   * Automatically updates `modified_by`, `modified_time`, and `version`.
   *
   * @param func Function to add `set` statements to an `UPDATE` query. This is called with the
   * [BATCHES] table as its receiver so that lambda functions can refer to column names without
   * having to qualify them (that is, `set(FOO, value)` instead of `set(BATCHES.FOO, value)`).
   * @throws BatchStaleException The batch's version number in the database wasn't the same as
   * [version].
   */
  private fun updateVersionedBatch(
      batchId: BatchId,
      version: Int,
      func: Batches.(UpdateSetFirstStep<BatchesRecord>) -> UpdateSetMoreStep<BatchesRecord>
  ) {
    val rowsUpdated =
        dslContext
            .update(BATCHES)
            .let { BATCHES.func(it) }
            .set(BATCHES.MODIFIED_BY, currentUser().userId)
            .set(BATCHES.MODIFIED_TIME, clock.instant())
            .set(BATCHES.VERSION, version + 1)
            .where(BATCHES.ID.eq(batchId))
            .and(BATCHES.VERSION.eq(version))
            .execute()
    if (rowsUpdated == 0) {
      val currentVersion =
          dslContext
              .select(BATCHES.VERSION)
              .from(BATCHES)
              .where(BATCHES.ID.eq(batchId))
              .fetchOne(BATCHES.VERSION)
      if (currentVersion == null) {
        throw BatchNotFoundException(batchId)
      } else if (currentVersion != version) {
        throw BatchStaleException(batchId, version)
      } else {
        log.error(
            "BUG! Update of batch $batchId version $version touched 0 rows but version is correct")
        throw IllegalStateException("Batch $batchId versions are inconsistent")
      }
    }
  }

  private fun insertQuantityHistoryRow(
      batchId: BatchId,
      germinatingQuantity: Int,
      notReadyQuantity: Int,
      readyQuantity: Int,
      historyType: BatchQuantityHistoryType
  ) {
    batchQuantityHistoryDao.insert(
        BatchQuantityHistoryRow(
            batchId = batchId,
            historyTypeId = historyType,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            readyQuantity = readyQuantity,
            notReadyQuantity = notReadyQuantity,
            germinatingQuantity = germinatingQuantity,
        ),
    )
  }
}
