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
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.daos.SubLocationsDao
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.Batches
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistorySubLocationsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchWithdrawalsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistorySubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.InventoriesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.nursery.tables.records.BatchesRecord
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.BatchDeletionStartedEvent
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.nursery.model.NurseryBatchPhase
import com.terraformation.backend.nursery.model.NurseryStats
import com.terraformation.backend.nursery.model.SpeciesSummary
import com.terraformation.backend.nursery.model.WithdrawalModel
import com.terraformation.backend.nursery.model.toModel
import com.terraformation.backend.seedbank.event.AccessionSpeciesChangedEvent
import jakarta.inject.Named
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.UpdateSetFirstStep
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class BatchStore(
    private val batchDetailsHistoryDao: BatchDetailsHistoryDao,
    private val batchDetailsHistorySubLocationsDao: BatchDetailsHistorySubLocationsDao,
    private val batchesDao: BatchesDao,
    private val batchQuantityHistoryDao: BatchQuantityHistoryDao,
    private val batchWithdrawalsDao: BatchWithdrawalsDao,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilitiesDao: FacilitiesDao,
    private val identifierGenerator: IdentifierGenerator,
    private val parentStore: ParentStore,
    private val projectsDao: ProjectsDao,
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

  /** The aggregate germination rate for a group of batches. */
  private val aggregateGerminationRateField: Field<Double?> =
      DSL.sum(BATCHES.TOTAL_GERMINATED)
          .times(100.0)
          .div(DSL.sum(BATCHES.TOTAL_GERMINATION_CANDIDATES))
          .cast(Double::class.java)

  /** The aggregate loss rate for a group of batches. */
  private val aggregateLossRateField: Field<Double?> =
      DSL.sum(BATCHES.TOTAL_LOST)
          .times(100.0)
          .div(DSL.sum(BATCHES.TOTAL_LOSS_CANDIDATES))
          .cast(Double::class.java)

  fun fetchOneById(batchId: BatchId): ExistingBatchModel {
    requirePermissions { readBatch(batchId) }

    val batchesRow = batchesDao.fetchOneById(batchId) ?: throw BatchNotFoundException(batchId)
    val subLocationIds = fetchSubLocationIds(batchId)
    val totalWithdrawn = getTotalWithdrawn(batchId)

    val accessionNumber = batchesRow.accessionId?.let { fetchAccessionNumber(it) }

    return ExistingBatchModel(batchesRow, subLocationIds, totalWithdrawn, accessionNumber)
  }

  fun fetchWithdrawalById(withdrawalId: WithdrawalId): ExistingWithdrawalModel {
    requirePermissions { readWithdrawal(withdrawalId) }

    val batchWithdrawalsRows = batchWithdrawalsDao.fetchByWithdrawalId(withdrawalId)
    val withdrawalsRow =
        withdrawalsDao.fetchOneById(withdrawalId) ?: throw WithdrawalNotFoundException(withdrawalId)
    val undoneByWithdrawalId =
        dslContext
            .select(WITHDRAWALS.ID)
            .from(WITHDRAWALS)
            .where(WITHDRAWALS.UNDOES_WITHDRAWAL_ID.eq(withdrawalId))
            .fetchOne(WITHDRAWALS.ID)

    return withdrawalsRow.toModel(batchWithdrawalsRows, undoneByWithdrawalId)
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
    val project =
        newModel.projectId?.let { projectId ->
          projectsDao.fetchOneById(projectId) ?: throw ProjectNotFoundException(projectId)
        }
    val facilityTimeZone = parentStore.getEffectiveTimeZone(newModel.facilityId)
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

    if (project != null && organizationId != project.organizationId) {
      throw ProjectInDifferentOrganizationException()
    }

    val subLocationNames =
        newModel.subLocationIds.associateWith { subLocationId ->
          val subLocationsRow =
              subLocationsDao.fetchOneById(subLocationId)
                  ?: throw SubLocationNotFoundException(subLocationId)

          if (newModel.facilityId != subLocationsRow.facilityId) {
            throw SubLocationAtWrongFacilityException(subLocationId)
          }

          subLocationsRow.name
        }

    // If the added date is in the past, backdate the latest observed quantity to midnight on the
    // added date so that any subsequent backdated withdrawals will count as quantity updates.
    val todayInFacilityTimeZone = ZonedDateTime.ofInstant(now, facilityTimeZone).toLocalDate()
    val latestObservedTime =
        if (newModel.addedDate < todayInFacilityTimeZone) {
          newModel.addedDate.atStartOfDay(facilityTimeZone).toInstant()
        } else {
          now
        }

    val rowWithDefaults =
        newModel
            .toRow()
            .copy(
                batchNumber =
                    newModel.batchNumber
                        ?: identifierGenerator.generateTextIdentifier(
                            organizationId, IdentifierType.BATCH, facilityNumber),
                createdBy = userId,
                createdTime = now,
                latestObservedTime = latestObservedTime,
                lossRate =
                    if (newModel.activeGrowthQuantity > 0 ||
                        newModel.hardeningOffQuantity > 0 ||
                        newModel.readyQuantity > 0)
                        0
                    else null,
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = organizationId,
                version = 1,
            )

    dslContext.transaction { _ ->
      batchesDao.insert(rowWithDefaults)

      insertQuantityHistoryRow(
          batchId = rowWithDefaults.id!!,
          germinatingQuantity = rowWithDefaults.germinatingQuantity!!,
          hardeningOffQuantity = rowWithDefaults.hardeningOffQuantity!!,
          historyType = BatchQuantityHistoryType.Observed,
          activeGrowthQuantity = rowWithDefaults.activeGrowthQuantity!!,
          readyQuantity = rowWithDefaults.readyQuantity!!,
          version = 1,
          withdrawalId = withdrawalId,
      )

      updateSubLocations(
          rowWithDefaults.id!!, newModel.facilityId, emptySet(), newModel.subLocationIds)

      val detailsHistoryRow =
          BatchDetailsHistoryRow(
              batchId = rowWithDefaults.id,
              createdBy = userId,
              createdTime = now,
              notes = newModel.notes,
              readyByDate = newModel.readyByDate,
              projectId = newModel.projectId,
              projectName = project?.name,
              substrateId = newModel.substrate,
              substrateNotes = newModel.substrateNotes,
              treatmentId = newModel.treatment,
              treatmentNotes = newModel.treatmentNotes,
              version = 1,
          )
      batchDetailsHistoryDao.insert(detailsHistoryRow)

      val detailsHistorySubLocationsRows =
          newModel.subLocationIds.map { subLocationId ->
            BatchDetailsHistorySubLocationsRow(
                batchDetailsHistoryId = detailsHistoryRow.id,
                subLocationId = subLocationId,
                subLocationName = subLocationNames[subLocationId])
          }
      batchDetailsHistorySubLocationsDao.insert(detailsHistorySubLocationsRows)
    }

    return ExistingBatchModel(rowWithDefaults, newModel.subLocationIds, 0)
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
                    BATCH_WITHDRAWALS.ACTIVE_GROWTH_QUANTITY_WITHDRAWN.plus(
                        BATCH_WITHDRAWALS.HARDENING_OFF_QUANTITY_WITHDRAWN.plus(
                            BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN))))
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

    val (germinationRate: Double?, lossRate: Double?) =
        dslContext
            .select(aggregateGerminationRateField, aggregateLossRateField)
            .from(BATCHES)
            .where(BATCHES.SPECIES_ID.eq(speciesId))
            .fetchOne()!!

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
        activeGrowthQuantity = inventory?.activeGrowthQuantity ?: 0,
        germinatingQuantity = inventory?.germinatingQuantity ?: 0,
        germinationRate = germinationRate?.roundToInt(),
        hardeningOffQuantity = inventory?.hardeningOffQuantity ?: 0,
        lossRate = lossRate?.roundToInt(),
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

    val project =
        updatedBatch.projectId?.let { projectId ->
          projectsDao.fetchOneById(projectId) ?: throw ProjectNotFoundException(projectId)
        }

    if (project != null && parentStore.getOrganizationId(batchId) != project.organizationId) {
      throw ProjectInDifferentOrganizationException()
    }

    val subLocationNames =
        updatedBatch.subLocationIds.associateWith { subLocationId ->
          val subLocationsRow =
              subLocationsDao.fetchOneById(subLocationId)
                  ?: throw SubLocationNotFoundException(subLocationId)

          if (subLocationsRow.facilityId != existingBatch.facilityId) {
            throw SubLocationAtWrongFacilityException(subLocationId)
          }

          subLocationsRow.name
        }

    val successFunc = { newVersion: Int ->
      updateSubLocations(
          batchId,
          existingBatch.facilityId,
          existingBatch.subLocationIds,
          updatedBatch.subLocationIds)

      if (existingBatch != updatedBatch) {
        val detailsHistoryRow =
            BatchDetailsHistoryRow(
                batchId = batchId,
                createdBy = currentUser().userId,
                createdTime = clock.instant(),
                germinationStartedDate = updatedBatch.germinationStartedDate,
                notes = updatedBatch.notes,
                readyByDate = updatedBatch.readyByDate,
                projectId = updatedBatch.projectId,
                projectName = project?.name,
                seedsSownDate = updatedBatch.seedsSownDate,
                substrateId = updatedBatch.substrate,
                substrateNotes = updatedBatch.substrateNotes,
                treatmentId = updatedBatch.treatment,
                treatmentNotes = updatedBatch.treatmentNotes,
                version = newVersion,
            )
        batchDetailsHistoryDao.insert(detailsHistoryRow)

        val detailsHistorySubLocationsRows =
            updatedBatch.subLocationIds.map { subLocationId ->
              BatchDetailsHistorySubLocationsRow(
                  batchDetailsHistoryId = detailsHistoryRow.id,
                  subLocationId = subLocationId,
                  subLocationName = subLocationNames[subLocationId])
            }
        batchDetailsHistorySubLocationsDao.insert(detailsHistorySubLocationsRows)
      }
    }

    updateVersionedBatch(batchId, version, successFunc) {
      it.set(NOTES, updatedBatch.notes)
          .set(GERMINATION_STARTED_DATE, updatedBatch.germinationStartedDate)
          .set(PROJECT_ID, updatedBatch.projectId)
          .set(READY_BY_DATE, updatedBatch.readyByDate)
          .set(SEEDS_SOWN_DATE, updatedBatch.seedsSownDate)
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
      activeGrowth: Int,
      hardeningOff: Int,
      ready: Int,
      historyType: BatchQuantityHistoryType,
      withdrawalId: WithdrawalId? = null,
  ) {
    if (germinating < 0 || activeGrowth < 0 || hardeningOff < 0 || ready < 0) {
      throw IllegalArgumentException("Quantities may not be negative")
    }

    requirePermissions { updateBatch(batchId) }

    val batch = fetchOneById(batchId)
    if (batch.germinatingQuantity == germinating &&
        batch.activeGrowthQuantity == activeGrowth &&
        batch.hardeningOffQuantity == hardeningOff &&
        batch.readyQuantity == ready) {
      return
    }

    dslContext.transaction { _ ->
      val successFunc = { newVersion: Int ->
        insertQuantityHistoryRow(
            batchId,
            germinating,
            activeGrowth,
            hardeningOff,
            ready,
            historyType,
            newVersion,
            withdrawalId)
        updateRates(batchId)
      }

      updateVersionedBatch(batchId, version, successFunc) { update ->
        update
            .set(GERMINATING_QUANTITY, germinating)
            .set(ACTIVE_GROWTH_QUANTITY, activeGrowth)
            .set(HARDENING_OFF_QUANTITY, hardeningOff)
            .set(READY_QUANTITY, ready)
            .let {
              if (historyType == BatchQuantityHistoryType.Observed) {
                it.set(LATEST_OBSERVED_GERMINATING_QUANTITY, germinating)
                    .set(LATEST_OBSERVED_ACTIVE_GROWTH_QUANTITY, activeGrowth)
                    .set(LATEST_OBSERVED_HARDENING_OFF_QUANTITY, hardeningOff)
                    .set(LATEST_OBSERVED_READY_QUANTITY, ready)
                    .set(LATEST_OBSERVED_TIME, clock.instant())
              } else {
                it
              }
            }
      }
    }
  }

  private fun calculateNewQuantities(
      batch: ExistingBatchModel,
      previousPhase: NurseryBatchPhase,
      newPhase: NurseryBatchPhase,
      quantityToChange: Int,
  ): Map<NurseryBatchPhase, Int> {
    val quantities =
        mutableMapOf(
            NurseryBatchPhase.Germinating to batch.germinatingQuantity,
            NurseryBatchPhase.ActiveGrowth to batch.activeGrowthQuantity,
            NurseryBatchPhase.HardeningOff to batch.hardeningOffQuantity,
            NurseryBatchPhase.Ready to batch.readyQuantity)

    val startingQuantity = quantities[previousPhase]!!
    if (startingQuantity < quantityToChange) {
      throw BatchInventoryInsufficientException(batch.id)
    }

    val convertedNewPhase =
        if (newPhase == NurseryBatchPhase.NotReady) NurseryBatchPhase.ActiveGrowth else newPhase
    quantities[previousPhase] = startingQuantity - quantityToChange
    quantities[convertedNewPhase] = quantities[convertedNewPhase]!! + quantityToChange

    return quantities
  }

  fun changeStatuses(
      batchId: BatchId,
      previousPhase: NurseryBatchPhase,
      newPhase: NurseryBatchPhase,
      quantityToChange: Int
  ) {
    requirePermissions { updateBatch(batchId) }

    if (quantityToChange == 0 || previousPhase == newPhase) {
      return
    }

    if (previousPhase > newPhase) {
      throw BatchPhaseReversalNotAllowedException(batchId)
    }

    retryVersionedBatchUpdate(batchId) { batch ->
      val newQuantities = calculateNewQuantities(batch, previousPhase, newPhase, quantityToChange)

      updateQuantities(
          batchId,
          batch.version,
          newQuantities[NurseryBatchPhase.Germinating]!!,
          newQuantities[NurseryBatchPhase.ActiveGrowth]!!,
          newQuantities[NurseryBatchPhase.HardeningOff]!!,
          newQuantities[NurseryBatchPhase.Ready]!!,
          BatchQuantityHistoryType.StatusChanged,
      )
    }
  }

  fun delete(batchId: BatchId) {
    requirePermissions { deleteBatch(batchId) }

    dslContext.transaction { _ ->
      if (!dslContext.fetchExists(BATCH_WITHDRAWALS, BATCH_WITHDRAWALS.BATCH_ID.eq(batchId))) {
        // The batch has no withdrawals; we can delete it outright with no effect on the nursery's
        // withdrawal history.
        log.info("Deleting batch $batchId")

        eventPublisher.publishEvent(BatchDeletionStartedEvent(batchId))

        dslContext.deleteFrom(BATCHES).where(BATCHES.ID.eq(batchId)).execute()
      } else {
        // "Delete" the batch by setting its remaining quantities to zero. This will cause it to be
        // filtered out of the list of active batches (deleting it from the inventory list), but
        // will preserve its withdrawal history.
        log.info("Emptying batch $batchId")

        retryVersionedBatchUpdate(batchId) { batch ->
          updateQuantities(batchId, batch.version, 0, 0, 0, 0, BatchQuantityHistoryType.Observed)
        }
      }
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
   * @param updateQuantityIfObservedBefore If this is earlier than the most recent quantity
   *   observation (manual edit) of a batch, the batch's remaining quantities won't be adjusted.
   * @param readyByDate If the withdrawal is a nursery transfer, the estimated ready-by date to use
   *   for the newly-created batches.
   */
  fun withdraw(
      withdrawal: NewWithdrawalModel,
      readyByDate: LocalDate? = null,
      updateQuantityIfObservedBefore: LocalDate = withdrawal.withdrawnDate,
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
              undoesWithdrawalId = withdrawal.undoesWithdrawalId,
          )

      withdrawalsDao.insert(withdrawalsRow)
      val withdrawalId = withdrawalsRow.id!!

      val destinationBatchIds: Map<BatchId, BatchId> =
          createDestinationBatches(withdrawalId, withdrawal, readyByDate)

      val batchWithdrawalsRows =
          withdrawal.batchWithdrawals
              // Sort by batch ID to avoid deadlocks if two clients withdraw from the same set of
              // batches at the same time; DB locks will be acquired in the same order on both.
              .sortedBy { it.batchId }
              .map { batchWithdrawal ->
                val batchId = batchWithdrawal.batchId
                val batchWithdrawalsRow =
                    BatchWithdrawalsRow(
                        batchId = batchId,
                        destinationBatchId = destinationBatchIds[batchId],
                        withdrawalId = withdrawalId,
                        germinatingQuantityWithdrawn = batchWithdrawal.germinatingQuantityWithdrawn,
                        hardeningOffQuantityWithdrawn =
                            batchWithdrawal.hardeningOffQuantityWithdrawn,
                        activeGrowthQuantityWithdrawn =
                            batchWithdrawal.activeGrowthQuantityWithdrawn,
                        readyQuantityWithdrawn = batchWithdrawal.readyQuantityWithdrawn)
                val nurseryTimeZone = parentStore.getEffectiveTimeZone(batchId)

                batchWithdrawalsDao.insert(batchWithdrawalsRow)

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
                      updateQuantityIfObservedBefore >= latestObservedDate

                  if (withdrawalIsNewerThanObservation) {
                    val newGerminatingQuantity =
                        batch.germinatingQuantity - batchWithdrawal.germinatingQuantityWithdrawn
                    val newActiveGrowthQuantity =
                        batch.activeGrowthQuantity - batchWithdrawal.activeGrowthQuantityWithdrawn
                    val newHardeningOffQuantity =
                        batch.hardeningOffQuantity - batchWithdrawal.hardeningOffQuantityWithdrawn
                    val newReadyQuantity =
                        batch.readyQuantity - batchWithdrawal.readyQuantityWithdrawn

                    if (newGerminatingQuantity < 0 ||
                        newActiveGrowthQuantity < 0 ||
                        newHardeningOffQuantity < 0 ||
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
                        newActiveGrowthQuantity,
                        newHardeningOffQuantity,
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
                }

                batchWithdrawalsRow
              }

      withdrawalsRow.toModel(batchWithdrawalsRows)
    }
  }

  /** Creates a new withdrawal to undo an existing withdrawal. */
  fun undoWithdrawal(withdrawalId: WithdrawalId): ExistingWithdrawalModel {
    val withdrawalToUndo = fetchWithdrawalById(withdrawalId)

    requirePermissions { withdrawalToUndo.batchWithdrawals.forEach { updateBatch(it.batchId) } }

    if (withdrawalToUndo.purpose == WithdrawalPurpose.NurseryTransfer) {
      throw UndoOfNurseryTransferNotAllowedException(withdrawalId)
    } else if (withdrawalToUndo.purpose == WithdrawalPurpose.Undo) {
      throw UndoOfUndoNotAllowedException(withdrawalId)
    }

    val isAlreadyUndone =
        dslContext.fetchExists(WITHDRAWALS, WITHDRAWALS.UNDOES_WITHDRAWAL_ID.eq(withdrawalId))
    if (isAlreadyUndone) {
      throw WithdrawalAlreadyUndoneException(withdrawalId)
    }

    val nurseryTimeZone = parentStore.getEffectiveTimeZone(withdrawalToUndo.facilityId)
    val todayAtNursery = LocalDate.ofInstant(clock.instant(), nurseryTimeZone)

    val batchWithdrawals =
        withdrawalToUndo.batchWithdrawals.map { batchWithdrawal ->
          BatchWithdrawalModel(
              batchId = batchWithdrawal.batchId,
              germinatingQuantityWithdrawn = -batchWithdrawal.germinatingQuantityWithdrawn,
              activeGrowthQuantityWithdrawn = -batchWithdrawal.activeGrowthQuantityWithdrawn,
              hardeningOffQuantityWithdrawn = -batchWithdrawal.hardeningOffQuantityWithdrawn,
              readyQuantityWithdrawn = -batchWithdrawal.readyQuantityWithdrawn,
          )
        }

    return withdraw(
        NewWithdrawalModel(
            batchWithdrawals = batchWithdrawals,
            facilityId = withdrawalToUndo.facilityId,
            id = null,
            purpose = WithdrawalPurpose.Undo,
            withdrawnDate = todayAtNursery,
            undoesWithdrawalId = withdrawalId,
        ),
        updateQuantityIfObservedBefore = withdrawalToUndo.withdrawnDate)
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
   * @param withdrawalId Withdrawal ID to include in the batch quantity history records of the new
   *   batches. The withdrawal ID, if any, in [withdrawal] is ignored.
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
            destinationBatch.activeGrowthQuantity!! + batchWithdrawal.activeGrowthQuantityWithdrawn,
            destinationBatch.hardeningOffQuantity!! + batchWithdrawal.hardeningOffQuantityWithdrawn,
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
                    hardeningOffQuantity = batchWithdrawal.hardeningOffQuantityWithdrawn,
                    initialBatchId = sourceBatch.id,
                    activeGrowthQuantity = batchWithdrawal.activeGrowthQuantityWithdrawn,
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
      successFunc: ((Int) -> Unit)? = null,
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
        successFunc?.invoke(version + 1)
      }
    }
  }

  private fun insertQuantityHistoryRow(
      batchId: BatchId,
      germinatingQuantity: Int,
      activeGrowthQuantity: Int,
      hardeningOffQuantity: Int,
      readyQuantity: Int,
      historyType: BatchQuantityHistoryType,
      version: Int,
      withdrawalId: WithdrawalId? = null,
  ) {
    batchQuantityHistoryDao.insert(
        BatchQuantityHistoryRow(
            batchId = batchId,
            historyTypeId = historyType,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            readyQuantity = readyQuantity,
            activeGrowthQuantity = activeGrowthQuantity,
            germinatingQuantity = germinatingQuantity,
            hardeningOffQuantity = hardeningOffQuantity,
            version = version,
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

  private fun getTotalWithdrawn(batchId: BatchId): Int {
    return dslContext
        .select(
            DSL.sum(
                BATCH_WITHDRAWALS.GERMINATING_QUANTITY_WITHDRAWN.plus(
                    BATCH_WITHDRAWALS.ACTIVE_GROWTH_QUANTITY_WITHDRAWN.plus(
                        BATCH_WITHDRAWALS.HARDENING_OFF_QUANTITY_WITHDRAWN.plus(
                            BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN)))))
        .from(BATCH_WITHDRAWALS)
        .where(BATCH_WITHDRAWALS.BATCH_ID.eq(batchId))
        .fetchOne()
        ?.value1()
        ?.toInt() ?: 0
  }

  fun getActiveSpecies(facilityId: FacilityId): List<SpeciesRow> {
    requirePermissions { readFacility(facilityId) }

    return dslContext
        .select(SPECIES.asterisk())
        .from(SPECIES)
        .where(
            SPECIES.ID.`in`(
                DSL.select(BATCHES.SPECIES_ID)
                    .from(BATCHES)
                    .where(BATCHES.FACILITY_ID.eq(facilityId))
                    .and(
                        DSL.or(
                            BATCHES.GERMINATING_QUANTITY.gt(0),
                            BATCHES.ACTIVE_GROWTH_QUANTITY.gt(0),
                            BATCHES.HARDENING_OFF_QUANTITY.gt(0),
                            BATCHES.READY_QUANTITY.gt(0)))))
        .orderBy(SPECIES.ID)
        .fetchInto(SpeciesRow::class.java)
  }

  fun getNurseryStats(
      facilityId: FacilityId? = null,
      projectId: ProjectId? = null,
      organizationId: OrganizationId? = null
  ): NurseryStats {
    if (facilityId == null && projectId == null && organizationId == null) {
      throw IllegalArgumentException(
          "Stats must be calculated by facility, project, or organization")
    }

    requirePermissions {
      facilityId?.let { readFacility(it) }
      organizationId?.let { readOrganization(it) }
      projectId?.let { readProject(it) }
    }

    val sumField =
        DSL.sum(
            BATCH_WITHDRAWALS.ACTIVE_GROWTH_QUANTITY_WITHDRAWN.plus(
                BATCH_WITHDRAWALS.HARDENING_OFF_QUANTITY_WITHDRAWN.plus(
                    BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN)))
    val undoneWithdrawals = WITHDRAWALS.`as`("undone_withdrawals")
    val effectivePurpose =
        DSL.coalesce(undoneWithdrawals.PURPOSE_ID, WITHDRAWALS.PURPOSE_ID).asNonNullable()

    val conditions =
        listOfNotNull(
            facilityId?.let { BATCHES.FACILITY_ID.eq(it) },
            projectId?.let { BATCHES.PROJECT_ID.eq(it) },
            organizationId?.let { BATCHES.ORGANIZATION_ID.eq(it) },
        )

    val withdrawnByPurpose =
        dslContext
            .select(effectivePurpose, sumField)
            .from(WITHDRAWALS)
            .join(BATCH_WITHDRAWALS)
            .on(WITHDRAWALS.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
            .join(BATCHES)
            .on(BATCH_WITHDRAWALS.BATCH_ID.eq(BATCHES.ID))
            .leftJoin(undoneWithdrawals)
            .on(WITHDRAWALS.UNDOES_WITHDRAWAL_ID.eq(undoneWithdrawals.ID))
            .where(conditions)
            .groupBy(effectivePurpose)
            .fetchMap(effectivePurpose, sumField)

    // The query results won't include purposes without any withdrawals, so add them.
    val withdrawnForAllPurposes =
        WithdrawalPurpose.entries
            .filterNot { it == WithdrawalPurpose.Undo }
            .associateWith { withdrawnByPurpose[it]?.toLong() ?: 0L }

    val inventoryTotals =
        dslContext
            .select(
                DSL.sum(BATCHES.GERMINATING_QUANTITY),
                DSL.sum(BATCHES.ACTIVE_GROWTH_QUANTITY),
                DSL.sum(BATCHES.HARDENING_OFF_QUANTITY),
                DSL.sum(BATCHES.READY_QUANTITY),
                aggregateGerminationRateField,
                aggregateLossRateField,
            )
            .from(BATCHES)
            .where(conditions)
            .fetchOne()

    return NurseryStats(
        facilityId = facilityId,
        germinationRate = inventoryTotals?.value5()?.roundToInt(),
        lossRate = inventoryTotals?.value6()?.roundToInt(),
        totalGerminating = inventoryTotals?.value1()?.toLong() ?: 0L,
        totalActiveGrowth = inventoryTotals?.value2()?.toLong() ?: 0L,
        totalHardeningOff = inventoryTotals?.value3()?.toLong() ?: 0L,
        totalReady = inventoryTotals?.value4()?.toLong() ?: 0L,
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

  private fun fetchAccessionNumber(accessionId: AccessionId): String? {
    return dslContext
        .select(ACCESSIONS.NUMBER)
        .from(ACCESSIONS)
        .where(ACCESSIONS.ID.eq(accessionId))
        .fetchOne(ACCESSIONS.NUMBER)
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

  /**
   * Updates the germination and loss rates for a batch. This does not count as a new version of the
   * batch. When this is called as part of a quantity update, the quantity history row for the new
   * update should be inserted before this is called.
   */
  private fun updateRates(batchId: BatchId) {
    val undoneWithdrawals = WITHDRAWALS.`as`("undone_withdrawals")
    val undonePurposeId = undoneWithdrawals.PURPOSE_ID.`as`("undone_purpose_id")

    val quantityHistory =
        dslContext
            .select(
                BATCH_QUANTITY_HISTORY.HISTORY_TYPE_ID,
                BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY,
                BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY,
                BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY,
                BATCH_QUANTITY_HISTORY.READY_QUANTITY,
                BATCH_WITHDRAWALS.BATCH_ID,
                BATCH_WITHDRAWALS.GERMINATING_QUANTITY_WITHDRAWN,
                BATCH_WITHDRAWALS.ACTIVE_GROWTH_QUANTITY_WITHDRAWN,
                BATCH_WITHDRAWALS.HARDENING_OFF_QUANTITY_WITHDRAWN,
                BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN,
                WITHDRAWALS.PURPOSE_ID,
                undonePurposeId,
            )
            .from(BATCH_QUANTITY_HISTORY)
            .leftJoin(BATCH_WITHDRAWALS)
            .on(BATCH_QUANTITY_HISTORY.BATCH_ID.eq(BATCH_WITHDRAWALS.BATCH_ID))
            .and(BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
            .leftJoin(WITHDRAWALS)
            .on(BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID.eq(WITHDRAWALS.ID))
            .leftJoin(undoneWithdrawals)
            .on(WITHDRAWALS.UNDOES_WITHDRAWAL_ID.eq(undoneWithdrawals.ID))
            .where(BATCH_QUANTITY_HISTORY.BATCH_ID.eq(batchId))
            .orderBy(BATCH_QUANTITY_HISTORY.VERSION)
            .fetch()

    if (quantityHistory.isEmpty()) {
      log.error("BUG! UpdateRates for batch $batchId called with no quantity history rows")
      return
    }

    val initialEvent = quantityHistory.first()
    val latestEvent = quantityHistory.last()

    var hasManualGerminatingEdit = false
    var hasManualHardeningOffEdit = false
    var hasManualActiveGrowthEdit = false
    var hasManualReadyEdit = false
    var hasAdditionalIncomingTransfers = false
    var totalWithdrawnNonGerminating = 0
    var totalNonDeadGerminating = 0
    var totalDeadNonGerminating = 0
    var totalOutplantAndDeadNonGerminating = 0

    // Walk through history starting at the second event, comparing each event to the previous one
    // to detect which values were edited and to tally up total withdrawals matching various
    // criteria as needed by the rate calculations.
    quantityHistory.reduce { previous, current ->
      val germinatingQuantityWithdrawn =
          current[BATCH_WITHDRAWALS.GERMINATING_QUANTITY_WITHDRAWN] ?: 0
      val hardeningOffQuantityWithdrawn =
          current[BATCH_WITHDRAWALS.HARDENING_OFF_QUANTITY_WITHDRAWN] ?: 0
      val activeGrowthQuantityWithdrawn =
          (current[BATCH_WITHDRAWALS.ACTIVE_GROWTH_QUANTITY_WITHDRAWN] ?: 0)
      val readyQuantityWithdrawn = current[BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN] ?: 0

      if (current[BATCH_QUANTITY_HISTORY.HISTORY_TYPE_ID] == BatchQuantityHistoryType.Observed) {
        // A manual edit, but we care about which of the specific quantities actually changed.
        hasManualGerminatingEdit =
            hasManualGerminatingEdit ||
                (current[BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY] !=
                    previous[BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY])
        hasManualActiveGrowthEdit =
            hasManualActiveGrowthEdit ||
                (current[BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY] !=
                    previous[BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY])
        hasManualHardeningOffEdit =
            hasManualHardeningOffEdit ||
                (current[BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY] !=
                    previous[BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY])
        hasManualReadyEdit =
            hasManualReadyEdit ||
                (current[BATCH_QUANTITY_HISTORY.READY_QUANTITY] !=
                    previous[BATCH_QUANTITY_HISTORY.READY_QUANTITY])
      }

      val purpose = current[WITHDRAWALS.PURPOSE_ID]
      val undonePurpose = current[undonePurposeId]

      if (purpose == WithdrawalPurpose.NurseryTransfer &&
          current[BATCH_WITHDRAWALS.BATCH_ID] != batchId) {
        hasAdditionalIncomingTransfers = true
      }

      if (purpose == WithdrawalPurpose.OutPlant ||
          purpose == WithdrawalPurpose.Dead ||
          undonePurpose == WithdrawalPurpose.OutPlant ||
          undonePurpose == WithdrawalPurpose.Dead) {
        totalOutplantAndDeadNonGerminating +=
            activeGrowthQuantityWithdrawn + hardeningOffQuantityWithdrawn + readyQuantityWithdrawn
      }

      if (purpose == WithdrawalPurpose.Dead || undonePurpose == WithdrawalPurpose.Dead) {
        totalDeadNonGerminating +=
            activeGrowthQuantityWithdrawn + hardeningOffQuantityWithdrawn + readyQuantityWithdrawn
      }

      if (purpose != null &&
          purpose != WithdrawalPurpose.Dead &&
          undonePurpose != WithdrawalPurpose.Dead) {
        totalNonDeadGerminating += germinatingQuantityWithdrawn
      }

      totalWithdrawnNonGerminating +=
          activeGrowthQuantityWithdrawn + hardeningOffQuantityWithdrawn + readyQuantityWithdrawn

      current
    }

    val initialGerminating = initialEvent[BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY]!!
    val initialActiveGrowth = initialEvent[BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY]!!
    val initialHardeningOff = initialEvent[BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY]!!
    val initialReady = initialEvent[BATCH_QUANTITY_HISTORY.READY_QUANTITY]!!
    val currentGerminating = latestEvent[BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY]!!
    val currentHardening = latestEvent[BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY]!!
    val currentNonGerminating =
        latestEvent[BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY]!! +
            latestEvent[BATCH_QUANTITY_HISTORY.READY_QUANTITY]!! +
            currentHardening

    val totalGerminated =
        currentNonGerminating + totalWithdrawnNonGerminating -
            initialActiveGrowth -
            initialHardeningOff -
            initialReady
    val totalGerminationCandidates = initialGerminating - totalNonDeadGerminating
    val germinationRate: Int? =
        if (totalGerminationCandidates > 0 &&
            currentGerminating == 0 &&
            !hasManualGerminatingEdit &&
            !hasManualActiveGrowthEdit &&
            !hasManualHardeningOffEdit &&
            !hasManualReadyEdit &&
            !hasAdditionalIncomingTransfers) {
          (100.0 * totalGerminated / totalGerminationCandidates).roundToInt()
        } else {
          null
        }

    val totalLost = totalDeadNonGerminating
    val totalLossCandidates = totalOutplantAndDeadNonGerminating + currentNonGerminating
    val lossRate: Int? =
        if (totalLossCandidates > 0 &&
            !hasManualActiveGrowthEdit &&
            !hasManualHardeningOffEdit &&
            !hasManualReadyEdit &&
            !hasAdditionalIncomingTransfers) {
          (100.0 * totalLost / totalLossCandidates).roundToInt()
        } else {
          null
        }

    dslContext
        .update(BATCHES)
        .set(BATCHES.GERMINATION_RATE, germinationRate)
        .set(BATCHES.TOTAL_GERMINATED, germinationRate?.let { totalGerminated })
        .set(
            BATCHES.TOTAL_GERMINATION_CANDIDATES,
            germinationRate?.let { totalGerminationCandidates })
        .set(BATCHES.LOSS_RATE, lossRate)
        .set(BATCHES.TOTAL_LOST, lossRate?.let { totalLost })
        .set(BATCHES.TOTAL_LOSS_CANDIDATES, lossRate?.let { totalLossCandidates })
        .where(BATCHES.ID.eq(batchId))
        .execute()
  }
}
