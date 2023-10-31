package com.terraformation.backend.nursery.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.IdentifierType
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.SubLocationAtWrongFacilityException
import com.terraformation.backend.db.SubLocationNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.SubLocationsDao
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
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.nursery.event.WithdrawalDeletionStartedEvent
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.nursery.model.NurseryStats
import com.terraformation.backend.nursery.model.SpeciesSummary
import com.terraformation.backend.nursery.model.WithdrawalModel
import com.terraformation.backend.nursery.model.toModel
import com.terraformation.backend.seedbank.event.AccessionSpeciesChangedEvent
import jakarta.inject.Named
import java.time.Clock
import java.time.LocalDate
import org.jooq.DSLContext
import org.jooq.UpdateSetFirstStep
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class BatchStore(
    private val batchesDao: BatchesDao,
    private val batchQuantityHistoryDao: BatchQuantityHistoryDao,
    private val batchWithdrawalsDao: BatchWithdrawalsDao,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilitiesDao: FacilitiesDao,
    private val identifierGenerator: IdentifierGenerator,
    private val parentStore: ParentStore,
    private val subLocationsDao: SubLocationsDao,
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

  fun fetchOneById(batchId: BatchId): ExistingBatchModel {
    requirePermissions { readBatch(batchId) }

    val subLocationIds = fetchSubLocationIds(batchId)

    return batchesDao.fetchOneById(batchId)?.let { ExistingBatchModel(it, subLocationIds) }
        ?: throw BatchNotFoundException(batchId)
  }

  fun fetchWithdrawalById(withdrawalId: WithdrawalId): ExistingWithdrawalModel {
    requirePermissions { readWithdrawal(withdrawalId) }

    val batchWithdrawalsRows = batchWithdrawalsDao.fetchByWithdrawalId(withdrawalId)
    val withdrawalsRow =
        withdrawalsDao.fetchOneById(withdrawalId) ?: throw WithdrawalNotFoundException(withdrawalId)

    return withdrawalsRow.toModel(batchWithdrawalsRows)
  }

  /**
   * Creates a new seedling batch.
   *
   * @param withdrawalId If this batch is being created by a transfer from another nursery, the ID
   *   of the withdrawal that is creating it.
   */
  fun create(newModel: NewBatchModel, withdrawalId: WithdrawalId? = null): ExistingBatchModel {
    val facility =
        facilitiesDao.fetchOneById(newModel.facilityId)
            ?: throw FacilityNotFoundException(newModel.facilityId)
    val facilityNumber = facility.facilityNumber!!
    val facilityType = facility.typeId!!
    val organizationId = facility.organizationId!!
    val speciesId =
        newModel.speciesId ?: throw IllegalArgumentException("Species ID must not be null")
    val now = clock.instant()
    val userId = currentUser().userId

    requirePermissions {
      createBatch(newModel.facilityId)
      newModel.projectId?.let { readProject(it) }
    }

    if (facilityType != FacilityType.Nursery) {
      throw FacilityTypeMismatchException(newModel.facilityId, FacilityType.Nursery)
    }

    if (organizationId != parentStore.getOrganizationId(speciesId)) {
      throw SpeciesNotFoundException(speciesId)
    }

    if (newModel.projectId != null &&
        organizationId != parentStore.getOrganizationId(newModel.projectId)) {
      throw ProjectInDifferentOrganizationException()
    }

    newModel.subLocationIds.forEach { subLocationId ->
      val subLocationsRow =
          subLocationsDao.fetchOneById(subLocationId)
              ?: throw SubLocationNotFoundException(subLocationId)

      if (newModel.facilityId != subLocationsRow.facilityId) {
        throw SubLocationAtWrongFacilityException(subLocationId)
      }
    }

    val rowWithDefaults =
        newModel
            .toRow()
            .copy(
                batchNumber = newModel.batchNumber
                        ?: identifierGenerator.generateIdentifier(
                            organizationId, IdentifierType.BATCH, facilityNumber),
                createdBy = userId,
                createdTime = now,
                latestObservedTime = now,
                modifiedBy = userId,
                modifiedTime = now,
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
          BatchQuantityHistoryType.Observed,
          withdrawalId)

      updateSubLocations(
          rowWithDefaults.id!!, newModel.facilityId, emptySet(), newModel.subLocationIds)
    }

    return ExistingBatchModel(rowWithDefaults, newModel.subLocationIds)
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
      applyChanges: (ExistingBatchModel) -> ExistingBatchModel,
  ) {
    requirePermissions { updateBatch(batchId) }

    val existingBatch = fetchOneById(batchId)
    val updatedBatch = applyChanges(existingBatch)

    requirePermissions { updatedBatch.projectId?.let { readProject(it) } }

    if (updatedBatch.projectId != null &&
        parentStore.getOrganizationId(batchId) !=
            parentStore.getOrganizationId(updatedBatch.projectId)) {
      throw ProjectInDifferentOrganizationException()
    }

    updatedBatch.subLocationIds.forEach { subLocationId ->
      val subLocationsRow =
          subLocationsDao.fetchOneById(subLocationId)
              ?: throw SubLocationNotFoundException(subLocationId)

      if (subLocationsRow.facilityId != existingBatch.facilityId) {
        throw SubLocationAtWrongFacilityException(subLocationId)
      }
    }

    val successFunc = {
      updateSubLocations(
          batchId,
          existingBatch.facilityId,
          existingBatch.subLocationIds,
          updatedBatch.subLocationIds)
    }

    updateVersionedBatch(batchId, version, successFunc) {
      it.set(NOTES, updatedBatch.notes)
          .set(PROJECT_ID, updatedBatch.projectId)
          .set(READY_BY_DATE, updatedBatch.readyByDate)
          .set(SUBSTRATE_ID, updatedBatch.substrate)
          .set(SUBSTRATE_NOTES, updatedBatch.substrateNotes)
          .set(TREATMENT_ID, updatedBatch.treatment)
          .set(TREATMENT_NOTES, updatedBatch.treatmentNotes)
    }
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

  fun changeStatuses(
      batchId: BatchId,
      germinatingQuantityToChange: Int,
      notReadyQuantityToChange: Int
  ) {
    requirePermissions { updateBatch(batchId) }

    if (germinatingQuantityToChange == 0 && notReadyQuantityToChange == 0) {
      return
    }

    retryVersionedBatchUpdate(batchId) { batch ->
      if (batch.germinatingQuantity < germinatingQuantityToChange ||
          batch.notReadyQuantity < notReadyQuantityToChange) {
        throw BatchInventoryInsufficientException(batchId)
      }

      updateQuantities(
          batchId,
          batch.version,
          batch.germinatingQuantity - germinatingQuantityToChange,
          batch.notReadyQuantity - notReadyQuantityToChange + germinatingQuantityToChange,
          batch.readyQuantity + notReadyQuantityToChange,
          BatchQuantityHistoryType.StatusChanged,
      )
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

  fun assignProject(projectId: ProjectId, batchIds: Collection<BatchId>) {
    requirePermissions { readProject(projectId) }

    if (batchIds.isEmpty()) {
      return
    }

    val projectOrganizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)
    val hasOtherOrganizationIds =
        dslContext
            .selectOne()
            .from(BATCHES)
            .where(BATCHES.ID.`in`(batchIds))
            .and(BATCHES.ORGANIZATION_ID.ne(projectOrganizationId))
            .limit(1)
            .fetch()
    if (hasOtherOrganizationIds.isNotEmpty) {
      throw ProjectInDifferentOrganizationException()
    }

    requirePermissions {
      // All batches are in the same organization, so it's sufficient to check permissions on
      // just one of them.
      updateBatch(batchIds.first())
    }

    with(BATCHES) {
      dslContext
          .update(BATCHES)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(PROJECT_ID, projectId)
          .where(ID.`in`(batchIds))
          .execute()
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
          createDestinationBatches(withdrawalId, withdrawal, readyByDate)

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
                val nurseryTimeZone = parentStore.getEffectiveTimeZone(batchId)

                retryVersionedBatchUpdate(batchId) { batch ->
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
                      LocalDate.ofInstant(batch.latestObservedTime, nurseryTimeZone)
                  val withdrawalIsNewerThanObservation =
                      withdrawal.withdrawnDate >= latestObservedDate

                  if (withdrawalIsNewerThanObservation) {
                    val newGerminatingQuantity =
                        batch.germinatingQuantity - batchWithdrawal.germinatingQuantityWithdrawn
                    val newNotReadyQuantity =
                        batch.notReadyQuantity - batchWithdrawal.notReadyQuantityWithdrawn
                    val newReadyQuantity =
                        batch.readyQuantity - batchWithdrawal.readyQuantityWithdrawn

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
                        batch.version,
                        newGerminatingQuantity,
                        newNotReadyQuantity,
                        newReadyQuantity,
                        BatchQuantityHistoryType.Computed,
                        withdrawalId)
                  } else {
                    updateVersionedBatch(batchId, batch.version) {
                      // Just update the version and modified user/timestamp (need a set() call
                      // here so the query object is of the correct jOOQ type)
                      it.set(emptyMap<Any, Any>())
                    }
                  }

                  batchWithdrawalsDao.insert(batchWithdrawalsRow)
                }

                batchWithdrawalsRow
              }

      withdrawalsRow.toModel(batchWithdrawalsRows)
    }
  }

  /**
   * Tries to update a batch, retrying a few times if the update failed because the version number
   * was stale due to a concurrent update.
   */
  private fun <T> retryVersionedBatchUpdate(
      batchId: BatchId,
      updateFunc: (ExistingBatchModel) -> T
  ): T {
    lateinit var batch: ExistingBatchModel
    var retriesRemaining = MAX_WITHDRAW_RETRIES

    // Optimistic locking: If some other thread updates a batch while we're in the
    // middle of checking whether or not we can withdraw from it, the version number
    // won't match the one we read from the database when we go to update it. In that
    // case, since the batch won't have been modified, we read it again (which will
    // give us its new version number as well as the latest quantity values) and retry.
    while (retriesRemaining-- > 0) {
      try {
        batch = fetchOneById(batchId)

        return updateFunc(batch)
      } catch (e: BatchStaleException) {
        if (retriesRemaining > 0) {
          log.debug("Batch $batchId was updated concurrently; retrying ($retriesRemaining)")
        }
      }
    }

    log.error("Batch $batchId was stale repeatedly; giving up")
    throw BatchStaleException(batchId, batch.version)
  }

  /**
   * For nursery transfer withdrawals, creates a batch at the destination facility for each batch
   * being withdrawn. The new batches' batch numbers use the same years and sequence numbers as the
   * original batches. If the destination facility already has a batch with that batch number, the
   * withdrawn quantity is added to the existing batch.
   *
   * @return A map of the originating batch IDs to the destination batch IDs.
   */
  private fun createDestinationBatches(
      withdrawalId: WithdrawalId,
      withdrawal: WithdrawalModel<*>,
      readyByDate: LocalDate?
  ): Map<BatchId, BatchId> {
    if (withdrawal.purpose != WithdrawalPurpose.NurseryTransfer) {
      return emptyMap()
    }

    val destinationFacilityId = withdrawal.destinationFacilityId ?: return emptyMap()
    val destinationFacility =
        facilitiesDao.fetchOneById(destinationFacilityId)
            ?: throw FacilityNotFoundException(destinationFacilityId)
    val destinationFacilityNumber = destinationFacility.facilityNumber!!
    val organizationId = destinationFacility.organizationId!!

    val batchesById: Map<BatchId, BatchesRow> =
        withdrawal.batchWithdrawals
            .map { batchesDao.fetchOneById(it.batchId) ?: throw BatchNotFoundException(it.batchId) }
            .associateBy { it.id!! }

    val destinationBatchNumbersBySourceBatchId: Map<BatchId, String?> =
        batchesById.mapValues { (_, batch) ->
          identifierGenerator.replaceFacilityNumber(batch.batchNumber!!, destinationFacilityNumber)
        }

    // Some of the desired destination batch numbers might already exist, others not.
    val existingDestinationBatchesByNumber: Map<String, BatchesRecord> =
        dslContext
            .selectFrom(BATCHES)
            .where(BATCHES.ORGANIZATION_ID.eq(organizationId))
            .and(BATCHES.FACILITY_ID.eq(destinationFacilityId))
            .and(BATCHES.BATCH_NUMBER.`in`(destinationBatchNumbersBySourceBatchId.values))
            .fetch()
            .associateBy { it.batchNumber!! }

    return withdrawal.batchWithdrawals.associate { batchWithdrawal ->
      val sourceBatch = batchesById[batchWithdrawal.batchId]!!
      val destinationBatchNumber = destinationBatchNumbersBySourceBatchId[batchWithdrawal.batchId]!!
      val destinationBatch = existingDestinationBatchesByNumber[destinationBatchNumber]

      if (destinationBatch != null) {
        updateQuantities(
            destinationBatch.id!!,
            destinationBatch.version!!,
            destinationBatch.germinatingQuantity!! + batchWithdrawal.germinatingQuantityWithdrawn,
            destinationBatch.notReadyQuantity!! + batchWithdrawal.notReadyQuantityWithdrawn,
            destinationBatch.readyQuantity!! + batchWithdrawal.readyQuantityWithdrawn,
            BatchQuantityHistoryType.Computed,
            withdrawalId,
        )

        batchWithdrawal.batchId to destinationBatch.id!!
      } else {
        val newBatch =
            create(
                NewBatchModel(
                    accessionId = sourceBatch.accessionId,
                    addedDate = withdrawal.withdrawnDate,
                    batchNumber = destinationBatchNumbersBySourceBatchId[batchWithdrawal.batchId],
                    facilityId = withdrawal.destinationFacilityId,
                    germinatingQuantity = batchWithdrawal.germinatingQuantityWithdrawn,
                    initialBatchId = sourceBatch.id,
                    notReadyQuantity = batchWithdrawal.notReadyQuantityWithdrawn,
                    readyByDate = readyByDate,
                    readyQuantity = batchWithdrawal.readyQuantityWithdrawn,
                    speciesId = sourceBatch.speciesId,
                ),
                withdrawalId)

        batchWithdrawal.batchId to newBatch.id
      }
    }
  }

  /**
   * Applies updates to a batch if the caller-supplied version number is up to date.
   *
   * Automatically updates `modified_by`, `modified_time`, and `version`.
   *
   * @param setFunc Function to add `set` statements to an `UPDATE` query. This is called with the
   *   [BATCHES] table as its receiver so that lambda functions can refer to column names without
   *   having to qualify them (that is, `set(FOO, value)` instead of `set(BATCHES.FOO, value)`).
   * @param successFunc Called in the same transaction as the update if the update succeeded; use
   *   this to apply changes to child tables.
   * @throws BatchStaleException The batch's version number in the database wasn't the same as
   *   [version].
   */
  private fun updateVersionedBatch(
      batchId: BatchId,
      version: Int,
      successFunc: (() -> Unit)? = null,
      setFunc: Batches.(UpdateSetFirstStep<BatchesRecord>) -> UpdateSetMoreStep<BatchesRecord>,
  ) {
    dslContext.transaction { _ ->
      val rowsUpdated =
          dslContext
              .update(BATCHES)
              .let { BATCHES.setFunc(it) }
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
      } else {
        successFunc?.invoke()
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
        WithdrawalPurpose.entries.associateWith { withdrawnByPurpose[it]?.toLong() ?: 0L }

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

  @EventListener
  fun on(event: AccessionSpeciesChangedEvent) {
    dslContext.transaction { _ ->
      dslContext
          .update(BATCHES)
          .set(BATCHES.MODIFIED_BY, currentUser().userId)
          .set(BATCHES.MODIFIED_TIME, clock.instant())
          .set(BATCHES.SPECIES_ID, event.newSpeciesId)
          .where(BATCHES.ACCESSION_ID.eq(event.accessionId))
          .and(BATCHES.SPECIES_ID.eq(event.oldSpeciesId))
          .execute()
    }
  }

  private fun fetchSubLocationIds(batchId: BatchId): Set<SubLocationId> {
    return dslContext
        .select(BATCH_SUB_LOCATIONS.SUB_LOCATION_ID)
        .from(BATCH_SUB_LOCATIONS)
        .where(BATCH_SUB_LOCATIONS.BATCH_ID.eq(batchId))
        .fetchSet(BATCH_SUB_LOCATIONS.SUB_LOCATION_ID.asNonNullable())
  }

  private fun updateSubLocations(
      batchId: BatchId,
      facilityId: FacilityId,
      existingSubLocationIds: Set<SubLocationId>,
      desiredSubLocationIds: Set<SubLocationId>
  ) {
    val subLocationIdsToAdd = desiredSubLocationIds - existingSubLocationIds
    val subLocationIdsToDelete = existingSubLocationIds - desiredSubLocationIds

    if (subLocationIdsToDelete.isNotEmpty()) {
      dslContext
          .deleteFrom(BATCH_SUB_LOCATIONS)
          .where(BATCH_SUB_LOCATIONS.BATCH_ID.eq(batchId))
          .and(BATCH_SUB_LOCATIONS.SUB_LOCATION_ID.`in`(subLocationIdsToDelete))
          .execute()
    }

    subLocationIdsToAdd.forEach { subLocationId ->
      dslContext
          .insertInto(BATCH_SUB_LOCATIONS)
          .set(BATCH_SUB_LOCATIONS.BATCH_ID, batchId)
          .set(BATCH_SUB_LOCATIONS.FACILITY_ID, facilityId)
          .set(BATCH_SUB_LOCATIONS.SUB_LOCATION_ID, subLocationId)
          .execute()
    }
  }
}
