package com.terraformation.backend.nursery.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.IdentifierType
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.Batches
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchWithdrawalsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.InventoriesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.nursery.tables.records.BatchesRecord
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.nursery.event.WithdrawalDeletionStartedEvent
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.nursery.model.NurseryStats
import com.terraformation.backend.nursery.model.SpeciesSummary
import com.terraformation.backend.nursery.model.WithdrawalModel
import com.terraformation.backend.nursery.model.toModel
import jakarta.inject.Named
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.UpdateSetFirstStep
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class BatchStore(
    private val batchesDao: BatchesDao,
    private val batchQuantityHistoryDao: BatchQuantityHistoryDao,
    private val batchWithdrawalsDao: BatchWithdrawalsDao,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val identifierGenerator: IdentifierGenerator,
    private val parentStore: ParentStore,
    private val withdrawalsDao: WithdrawalsDao,
) {
  companion object {
    /**
     * Number of times we will retry withdrawing from a batch if its version number is stale. This
     * should only happen if another client is withdrawing from the same batch at the same time,
     * which should be exceedingly rare.
     */
    private const val MAX_WITHDRAW_RETRIES = 3
  }

  private val log = perClassLogger()

  fun fetchOneById(batchId: BatchId): BatchesRow {
    requirePermissions { readBatch(batchId) }

    return batchesDao.fetchOneById(batchId) ?: throw BatchNotFoundException(batchId)
  }

  fun fetchWithdrawalById(withdrawalId: WithdrawalId): ExistingWithdrawalModel {
    requirePermissions { readWithdrawal(withdrawalId) }

    val batchWithdrawalsRows = batchWithdrawalsDao.fetchByWithdrawalId(withdrawalId)
    val withdrawalsRow =
        withdrawalsDao.fetchOneById(withdrawalId) ?: throw WithdrawalNotFoundException(withdrawalId)

    return withdrawalsRow.toModel(batchWithdrawalsRows)
  }

  fun create(row: BatchesRow): BatchesRow {
    val facilityId =
        row.facilityId ?: throw IllegalArgumentException("Facility ID must be non-null")
    val facilityType = parentStore.getFacilityType(facilityId)
    val organizationId =
        parentStore.getOrganizationId(facilityId)
            ?: throw IllegalArgumentException("Facility not found")
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
            batchNumber =
                identifierGenerator.generateIdentifier(organizationId, IdentifierType.BATCH),
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
      withdrawalId: WithdrawalId? = null,
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

      insertQuantityHistoryRow(batchId, germinating, notReady, ready, historyType, withdrawalId)
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
      val withdrawalIds =
          dslContext
              .select(WITHDRAWALS.ID)
              .from(WITHDRAWALS)
              .join(BATCH_WITHDRAWALS)
              .on(WITHDRAWALS.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
              .where(BATCH_WITHDRAWALS.BATCH_ID.eq(batchId))
              .andNotExists(
                  DSL.selectOne()
                      .from(BATCH_WITHDRAWALS)
                      .where(WITHDRAWALS.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
                      .and(BATCH_WITHDRAWALS.BATCH_ID.ne(batchId)))
              .fetch(WITHDRAWALS.ID.asNonNullable())

      if (withdrawalIds.isNotEmpty()) {
        withdrawalIds.forEach { eventPublisher.publishEvent(WithdrawalDeletionStartedEvent(it)) }

        dslContext.deleteFrom(WITHDRAWALS).where(WITHDRAWALS.ID.`in`(withdrawalIds)).execute()
      }

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
   * Withdraws from one or more batches. All batches must be at the same facility, but may be of
   * different species.
   *
   * @param readyByDate If the withdrawal is a nursery transfer, the estimated ready-by date to use
   *   for the newly-created batches.
   */
  fun withdraw(
      withdrawal: NewWithdrawalModel,
      readyByDate: LocalDate? = null
  ): ExistingWithdrawalModel {
    withdrawal.batchWithdrawals.forEach { batchWithdrawal ->
      requirePermissions { updateBatch(batchWithdrawal.batchId) }

      if (withdrawal.facilityId != parentStore.getFacilityId(batchWithdrawal.batchId)) {
        throw IllegalArgumentException("All batches in a withdrawal must be from the same facility")
      }
    }

    if (withdrawal.batchWithdrawals.map { it.batchId }.distinct().size <
        withdrawal.batchWithdrawals.size) {
      throw IllegalArgumentException(
          "Cannot withdraw from the same batch more than once in a single withdrawal")
    }

    if (withdrawal.purpose == WithdrawalPurpose.NurseryTransfer) {
      if (withdrawal.destinationFacilityId == null) {
        throw IllegalArgumentException("Nursery transfer must include destination facility ID")
      }

      requirePermissions { createBatch(withdrawal.destinationFacilityId) }

      if (parentStore.getOrganizationId(withdrawal.facilityId) !=
          parentStore.getOrganizationId(withdrawal.destinationFacilityId)) {
        throw CrossOrganizationNurseryTransferNotAllowedException(
            withdrawal.facilityId, withdrawal.destinationFacilityId)
      }
    } else if (withdrawal.destinationFacilityId != null) {
      throw IllegalArgumentException("Only nursery transfers may include destination facility ID")
    }

    return dslContext.transactionResult { _ ->
      val withdrawalsRow =
          WithdrawalsRow(
              createdBy = currentUser().userId,
              createdTime = clock.instant(),
              destinationFacilityId = withdrawal.destinationFacilityId,
              facilityId = withdrawal.facilityId,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
              notes = withdrawal.notes,
              purposeId = withdrawal.purpose,
              withdrawnDate = withdrawal.withdrawnDate,
          )

      withdrawalsDao.insert(withdrawalsRow)
      val withdrawalId = withdrawalsRow.id!!

      val destinationBatchIds: Map<BatchId, BatchId> =
          createDestinationBatches(withdrawal, readyByDate)

      val batchWithdrawalsRows =
          withdrawal.batchWithdrawals
              // Sort by batch ID to avoid deadlocks if two clients withdraw from the same set of
              // batches at the same time; DB locks will be acquired in the same order on both.
              .sortedBy { it.batchId.value }
              .map { batchWithdrawal ->
                val batchId = batchWithdrawal.batchId
                val batchWithdrawalsRow =
                    BatchWithdrawalsRow(
                        batchId = batchId,
                        destinationBatchId = destinationBatchIds[batchId],
                        withdrawalId = withdrawalId,
                        germinatingQuantityWithdrawn = batchWithdrawal.germinatingQuantityWithdrawn,
                        notReadyQuantityWithdrawn = batchWithdrawal.notReadyQuantityWithdrawn,
                        readyQuantityWithdrawn = batchWithdrawal.readyQuantityWithdrawn)

                var retriesRemaining = MAX_WITHDRAW_RETRIES
                var succeeded = false

                // Optimistic locking: If some other thread updates a batch while we're in the
                // middle of checking whether or not we can withdraw from it, the version number
                // won't match the one we read from the database when we go to update it. In that
                // case, since the batch won't have been modified, we read it again (which will
                // give us its new version number as well as the latest quantity values) and retry.
                while (!succeeded && retriesRemaining-- > 0) {
                  try {
                    val batch = fetchOneById(batchId)

                    // Usually we want to subtract the withdrawal amounts from the batch's
                    // available quantities. However, if the user is entering data about an older
                    // withdrawal, and they have explicitly edited the available quantities more
                    // recently than the withdrawal, just record the withdrawal without subtracting
                    // it from inventory.
                    //
                    // We figure this out inside the retry loop because the latest observed time
                    // might have been updated by whatever operation caused our version number to
                    // be out of date.
                    val latestObservedDate =
                        LocalDate.ofInstant(batch.latestObservedTime!!, ZoneOffset.UTC)
                    val withdrawalIsNewerThanObservation =
                        withdrawal.withdrawnDate >= latestObservedDate

                    if (withdrawalIsNewerThanObservation) {
                      val newGerminatingQuantity =
                          batch.germinatingQuantity!! - batchWithdrawal.germinatingQuantityWithdrawn
                      val newNotReadyQuantity =
                          batch.notReadyQuantity!! - batchWithdrawal.notReadyQuantityWithdrawn
                      val newReadyQuantity =
                          batch.readyQuantity!! - batchWithdrawal.readyQuantityWithdrawn

                      if (newGerminatingQuantity < 0 ||
                          newNotReadyQuantity < 0 ||
                          newReadyQuantity < 0) {
                        throw BatchInventoryInsufficientException(batchId)
                      }

                      // Update the batch's quantities to reflect the withdrawal. If another thread
                      // has updated the batch since we fetched it above, the version number won't
                      // match and BatchStaleException will be thrown, which will cause us to try
                      // again.
                      updateQuantities(
                          batchId,
                          batch.version!!,
                          newGerminatingQuantity,
                          newNotReadyQuantity,
                          newReadyQuantity,
                          BatchQuantityHistoryType.Computed,
                          withdrawalId)
                    } else {
                      updateVersionedBatch(batchId, batch.version!!) {
                        // Just update the version and modified user/timestamp (need a set() call
                        // here so the query object is of the correct jOOQ type)
                        it.set(emptyMap<Any, Any>())
                      }
                    }

                    batchWithdrawalsDao.insert(batchWithdrawalsRow)

                    succeeded = true
                  } catch (e: BatchStaleException) {
                    if (retriesRemaining > 0) {
                      log.debug(
                          "Batch $batchId was updated concurrently; retrying withdrawal ($retriesRemaining)")
                    } else {
                      log.error("Batch $batchId was stale repeatedly; giving up")
                      throw e
                    }
                  }
                }

                if (retriesRemaining < 0) {
                  log.error("BUG! Should have aborted after batch $batchId was stale repeatedly")
                  throw IllegalStateException("Unable to withdraw from seedling batch")
                }

                batchWithdrawalsRow
              }

      withdrawalsRow.toModel(batchWithdrawalsRows)
    }
  }

  /**
   * For nursery transfer withdrawals, creates a batch at the destination facility for each species
   * being withdrawn.
   *
   * @return A map of the originating batch IDs to the newly-created batch IDs. This is an N:1
   *   mapping: if you withdraw from two batches of the same species, only one new batch will be
   *   created at the destination facility.
   */
  private fun createDestinationBatches(
      withdrawal: WithdrawalModel<*>,
      readyByDate: LocalDate?
  ): Map<BatchId, BatchId> {
    if (withdrawal.purpose != WithdrawalPurpose.NurseryTransfer) {
      return emptyMap()
    }

    // We want to create a new batch for each species, rather than one for each originating
    // batch, so we need to look up the species ID of the originating bach of each batch withdrawal
    // in order to aggregate the per-species quantities.
    val batchWithdrawalsBySpeciesId =
        withdrawal.batchWithdrawals.groupBy { batchesDao.fetchOneById(it.batchId)?.speciesId }

    return batchWithdrawalsBySpeciesId
        .flatMap { (speciesId, batchWithdrawals) ->
          val germinatingQuantity = batchWithdrawals.sumOf { it.germinatingQuantityWithdrawn }
          val notReadyQuantity = batchWithdrawals.sumOf { it.notReadyQuantityWithdrawn }
          val readyQuantity = batchWithdrawals.sumOf { it.readyQuantityWithdrawn }

          val newBatch =
              create(
                  BatchesRow(
                      addedDate = withdrawal.withdrawnDate,
                      facilityId = withdrawal.destinationFacilityId,
                      germinatingQuantity = germinatingQuantity,
                      notReadyQuantity = notReadyQuantity,
                      readyByDate = readyByDate,
                      readyQuantity = readyQuantity,
                      speciesId = speciesId))

          // Return a List<Pair<BatchId, BatchId>> mapping the originating batch IDs to the
          // newly-created one. The List will get flattened by flatMap and then turned into
          // Map<BatchId, BatchId>.
          batchWithdrawals.map { it.batchId to newBatch.id!! }
        }
        .toMap()
  }

  /**
   * Applies an update to a batch if the caller-supplied version number is up to date.
   *
   * Automatically updates `modified_by`, `modified_time`, and `version`.
   *
   * @param func Function to add `set` statements to an `UPDATE` query. This is called with the
   *   [BATCHES] table as its receiver so that lambda functions can refer to column names without
   *   having to qualify them (that is, `set(FOO, value)` instead of `set(BATCHES.FOO, value)`).
   * @throws BatchStaleException The batch's version number in the database wasn't the same as
   *   [version].
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
      historyType: BatchQuantityHistoryType,
      withdrawalId: WithdrawalId? = null,
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
            withdrawalId = withdrawalId,
        ),
    )
  }

  fun fetchEstimatedReady(
      facilityId: FacilityId,
      after: LocalDate,
      until: LocalDate
  ): List<NurserySeedlingBatchReadyEvent> {
    return with(BATCHES) {
      dslContext
          .select(ID, BATCH_NUMBER, SPECIES_ID, FACILITIES.NAME)
          .from(BATCHES)
          .join(FACILITIES)
          .on(BATCHES.FACILITY_ID.eq(FACILITIES.ID))
          .where(READY_BY_DATE.le(until))
          .and(READY_BY_DATE.gt(after))
          .and(FACILITY_ID.eq(facilityId))
          .fetch {
            NurserySeedlingBatchReadyEvent(
                it[ID]!!, it[BATCH_NUMBER]!!, it[SPECIES_ID]!!, it[FACILITIES.NAME]!!)
          }
    }
  }

  fun getNurseryStats(facilityId: FacilityId): NurseryStats {
    requirePermissions { readFacility(facilityId) }

    val sumField =
        DSL.sum(
            BATCH_WITHDRAWALS.NOT_READY_QUANTITY_WITHDRAWN.plus(
                BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN))
    val withdrawnByPurpose =
        dslContext
            .select(WITHDRAWALS.PURPOSE_ID, sumField)
            .from(WITHDRAWALS)
            .join(BATCH_WITHDRAWALS)
            .on(WITHDRAWALS.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
            .where(WITHDRAWALS.FACILITY_ID.eq(facilityId))
            .groupBy(WITHDRAWALS.PURPOSE_ID)
            .fetchMap(WITHDRAWALS.PURPOSE_ID.asNonNullable(), sumField)

    // The query results won't include purposes without any withdrawals, so add them.
    val withdrawnForAllPurposes =
        WithdrawalPurpose.values().associateWith { withdrawnByPurpose[it]?.toLong() ?: 0L }

    val inventoryTotals =
        dslContext
            .select(
                DSL.sum(BATCHES.GERMINATING_QUANTITY),
                DSL.sum(BATCHES.NOT_READY_QUANTITY),
                DSL.sum(BATCHES.READY_QUANTITY))
            .from(BATCHES)
            .where(BATCHES.FACILITY_ID.eq(facilityId))
            .fetchOne()

    return NurseryStats(
        facilityId = facilityId,
        totalGerminating = inventoryTotals?.value1()?.toLong() ?: 0L,
        totalNotReady = inventoryTotals?.value2()?.toLong() ?: 0L,
        totalReady = inventoryTotals?.value3()?.toLong() ?: 0L,
        totalWithdrawnByPurpose = withdrawnForAllPurposes,
    )
  }
}
