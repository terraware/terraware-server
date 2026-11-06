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
import com.terraformation.backend.db.nursery.BatchStage
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
import com.terraformation.backend.db.nursery.tables.pojos.BatchStageDetailsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.InventoriesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.nursery.tables.records.BatchesRecord
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.db.nursery.tables.references.BATCH_STAGE_DETAILS
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose as SeedbankWithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS as SEEDBANK_WITHDRAWALS
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.BatchDeletionStartedEvent
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.nursery.event.WithdrawalAssociatedWithPlantingDateRequestEvent
import com.terraformation.backend.nursery.model.BatchStageDetailsModel
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.nursery.model.NurseryBatchPhase
import com.terraformation.backend.nursery.model.NurseryStats
import com.terraformation.backend.nursery.model.SpeciesStageTimeModel
import com.terraformation.backend.nursery.model.SpeciesSummary
import com.terraformation.backend.nursery.model.StageTimeCalculator
import com.terraformation.backend.nursery.model.StageTimeEvent
import com.terraformation.backend.nursery.model.StageTimeEventType
import com.terraformation.backend.nursery.model.StageTimeStats
import com.terraformation.backend.nursery.model.WithdrawalModel
import com.terraformation.backend.nursery.model.toModel
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonDateRequestFulfilledException
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonDateRequestNotFoundException
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonScheduledDateNotFoundException
import com.terraformation.backend.plantingmanagement.db.SeasonHelper
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonWithdrawalCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionSpeciesChangedEvent
import jakarta.inject.Named
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
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
    private val seasonHelper: SeasonHelper,
    private val subLocationsDao: SubLocationsDao,
    private val withdrawalsDao: WithdrawalsDao,
) {
  companion object {
    /**
     * When Hardening Off was released to production. Active Growth times of batches created before
     * then include time that would now count as Hardening Off, so they aren't reported.
     */
    private val HARDENING_OFF_RELEASE_TIME = Instant.parse("2025-08-28T12:56:00-07:00")

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

  private fun fetchQuantities(batchId: BatchId): Map<BatchStage, BatchStageDetailsModel> =
      dslContext
          .selectFrom(BATCH_STAGE_DETAILS)
          .where(BATCH_STAGE_DETAILS.BATCH_ID.eq(batchId))
          .fetch { it.into(BatchStageDetailsRow::class.java).toModel() }
          .associateBy { it.stage }

  fun fetchOneById(batchId: BatchId): ExistingBatchModel {
    requirePermissions { readBatch(batchId) }

    val batchesRow = batchesDao.fetchOneById(batchId) ?: throw BatchNotFoundException(batchId)
    val quantities = fetchQuantities(batchId)
    val accessionNumbers = fetchAccessionNumbers(batchId)
    val subLocationIds = fetchSubLocationIds(batchId)
    val totalWithdrawn = getTotalWithdrawn(batchId)

    return ExistingBatchModel(
        batchesRow,
        subLocationIds,
        totalWithdrawn,
        accessionNumbers,
        quantities,
    )
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

    if (newModel.addedDate > todayInFacilityTimeZone) {
      throw IllegalArgumentException("Added date cannot be in the future")
    }

    val rowWithDefaults =
        newModel
            .toRow()
            .copy(
                batchNumber =
                    newModel.batchNumber
                        ?: identifierGenerator.generateTextIdentifier(
                            organizationId,
                            IdentifierType.BATCH,
                            facilityNumber,
                        ),
                createdBy = userId,
                createdTime = now,
                latestObservedTime = latestObservedTime,
                lossRate =
                    if (
                        newModel.activeGrowthQuantity > 0 ||
                            newModel.hardeningOffQuantity > 0 ||
                            newModel.readyQuantity > 0
                    )
                        0
                    else null,
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = organizationId,
                version = 1,
            )

    dslContext.transaction { _ ->
      batchesDao.insert(rowWithDefaults)
      writeStageQuantities(
          rowWithDefaults.id!!,
          newModel.germinatingQuantity,
          newModel.activeGrowthQuantity,
          newModel.hardeningOffQuantity,
          newModel.readyQuantity,
          observed = true,
      )

      insertQuantityHistoryRow(
          accessionId = newModel.accessionId,
          activeGrowthQuantity = newModel.activeGrowthQuantity,
          batchId = rowWithDefaults.id!!,
          germinatingQuantity = newModel.germinatingQuantity,
          hardeningOffQuantity = newModel.hardeningOffQuantity,
          historyType = BatchQuantityHistoryType.Observed,
          readyQuantity = newModel.readyQuantity,
          userSuppliedDate = if (withdrawalId != null) newModel.addedDate else null,
          version = 1,
          withdrawalId = withdrawalId,
      )
      updateTimeInStage(rowWithDefaults.id!!)

      updateSubLocations(
          rowWithDefaults.id!!,
          newModel.facilityId,
          emptySet(),
          newModel.subLocationIds,
      )

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
                subLocationName = subLocationNames[subLocationId],
            )
          }
      batchDetailsHistorySubLocationsDao.insert(detailsHistorySubLocationsRows)
    }

    return ExistingBatchModel(
        row = rowWithDefaults,
        quantities = fetchQuantities(rowWithDefaults.id!!),
        subLocationIds = newModel.subLocationIds,
        totalWithdrawn = 0,
        accessionNumbers = fetchAccessionNumbers(rowWithDefaults.id!!),
    )
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
                            BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN
                        )
                    )
                ),
            )
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

    val stageTimes =
        with(BATCH_STAGE_DETAILS) {
          val qualifiesForRange =
              FINISHED_QUANTITY.ge(SpeciesStageTimeModel.MIN_TYPICAL_RANGE_FINISHED_QUANTITY)
          val batchAverage = FINISHED_SEEDLING_DAYS.div(FINISHED_QUANTITY)
          dslContext
              .select(
                  STAGE_ID,
                  DSL.sum(FINISHED_SEEDLING_DAYS).div(DSL.sum(FINISHED_QUANTITY)),
                  DSL.count(),
                  DSL.percentileCont(0.25)
                      .withinGroupOrderBy(batchAverage)
                      .filterWhere(qualifiesForRange),
                  DSL.percentileCont(0.75)
                      .withinGroupOrderBy(batchAverage)
                      .filterWhere(qualifiesForRange),
                  DSL.count().filterWhere(qualifiesForRange),
              )
              .from(BATCH_STAGE_DETAILS)
              .join(BATCHES)
              .on(BATCHES.ID.eq(BATCH_ID))
              .where(BATCHES.SPECIES_ID.eq(speciesId))
              .and(FINISHED_QUANTITY.gt(0.0))
              .and(TIME_IN_STAGE_CLEARED.isDistinctFrom(true))
              .groupBy(STAGE_ID)
              .fetch()
              .associate { row ->
                row.value1()!! to
                    SpeciesStageTimeModel.of(
                        averageDays = row.value2()?.toDouble(),
                        batchCount = row.value3(),
                        typicalMaxDays = row.value5()?.toDouble(),
                        typicalMinDays = row.value4()?.toDouble(),
                        typicalRangeBatchCount = row.value6(),
                    )
              }
        }

    return SpeciesSummary(
        activeGrowthQuantity = inventory?.activeGrowthQuantity ?: 0,
        averageTimeInStage =
            BatchStage.entries.associateWith { stageTimes[it] ?: SpeciesStageTimeModel.of() },
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
          updatedBatch.subLocationIds,
      )

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
                  subLocationName = subLocationNames[subLocationId],
              )
            }
        batchDetailsHistorySubLocationsDao.insert(detailsHistorySubLocationsRows)
      }

      if (
          existingBatch.seedsSownDate != updatedBatch.seedsSownDate ||
              existingBatch.germinationStartedDate != updatedBatch.germinationStartedDate
      ) {
        updateTimeInStage(batchId)
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
      notes: String? = null,
      accessionId: AccessionId? = null,
      userSuppliedDate: LocalDate? = null,
  ) {
    if (germinating < 0 || activeGrowth < 0 || hardeningOff < 0 || ready < 0) {
      throw IllegalArgumentException("Quantities may not be negative")
    }

    requirePermissions { updateBatch(batchId) }

    val batch = fetchOneById(batchId)
    if (
        batch.germinatingQuantity == germinating &&
            batch.activeGrowthQuantity == activeGrowth &&
            batch.hardeningOffQuantity == hardeningOff &&
            batch.readyQuantity == ready
    ) {
      return
    }

    dslContext.transaction { _ ->
      val successFunc = { newVersion: Int ->
        writeStageQuantities(
            batchId,
            germinating,
            activeGrowth,
            hardeningOff,
            ready,
            observed = historyType == BatchQuantityHistoryType.Observed,
        )
        insertQuantityHistoryRow(
            batchId,
            germinating,
            activeGrowth,
            hardeningOff,
            ready,
            historyType,
            newVersion,
            withdrawalId,
            notes,
            accessionId,
            userSuppliedDate,
        )
        updateRates(batchId)
        updateTimeInStage(batchId)
      }

      updateVersionedBatch(batchId, version, successFunc) { update ->
        if (historyType == BatchQuantityHistoryType.Observed) {
          update.set(LATEST_OBSERVED_TIME, clock.instant())
        }
      }
    }
  }

  fun addToExistingBatch(
      accessionId: AccessionId,
      addedDate: LocalDate,
      batchId: BatchId,
      germinatingQuantity: Int,
  ): ExistingBatchModel {
    requirePermissions { updateBatch(batchId) }

    if (germinatingQuantity < 0) {
      throw IllegalArgumentException("Quantities may not be negative")
    }

    return dslContext.transactionResult { _ ->
      retryVersionedBatchUpdate(batchId) { batch ->
        updateQuantities(
            accessionId = accessionId,
            activeGrowth = batch.activeGrowthQuantity,
            batchId = batchId,
            germinating = batch.germinatingQuantity + germinatingQuantity,
            hardeningOff = batch.hardeningOffQuantity,
            historyType = BatchQuantityHistoryType.Computed,
            ready = batch.readyQuantity,
            userSuppliedDate = addedDate,
            version = batch.version,
        )
      }

      fetchOneById(batchId)
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
            NurseryBatchPhase.Ready to batch.readyQuantity,
        )

    val convertedPreviousPhase =
        if (previousPhase == NurseryBatchPhase.NotReady) NurseryBatchPhase.ActiveGrowth
        else previousPhase
    val convertedNewPhase =
        if (newPhase == NurseryBatchPhase.NotReady) NurseryBatchPhase.ActiveGrowth else newPhase

    val startingQuantity = quantities[convertedPreviousPhase]!!
    if (startingQuantity < quantityToChange) {
      throw BatchInventoryInsufficientException(batch.id)
    }

    quantities[convertedPreviousPhase] = startingQuantity - quantityToChange
    quantities[convertedNewPhase] = quantities[convertedNewPhase]!! + quantityToChange

    return quantities
  }

  fun changeStatuses(
      batchId: BatchId,
      previousPhase: NurseryBatchPhase,
      newPhase: NurseryBatchPhase,
      quantityToChange: Int,
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

    if (
        withdrawal.batchWithdrawals.map { it.batchId }.distinct().size <
            withdrawal.batchWithdrawals.size
    ) {
      throw IllegalArgumentException(
          "Cannot withdraw from the same batch more than once in a single withdrawal"
      )
    }

    if (withdrawal.purpose == WithdrawalPurpose.NurseryTransfer) {
      if (withdrawal.destinationFacilityId == null) {
        throw IllegalArgumentException("Nursery transfer must include destination facility ID")
      }
      if (withdrawal.facilityId == withdrawal.destinationFacilityId) {
        throw IllegalArgumentException("Cannot transfer from a nursery to itself")
      }

      requirePermissions { createBatch(withdrawal.destinationFacilityId) }

      if (
          parentStore.getOrganizationId(withdrawal.facilityId) !=
              parentStore.getOrganizationId(withdrawal.destinationFacilityId)
      ) {
        throw CrossOrganizationNurseryTransferNotAllowedException(
            withdrawal.facilityId,
            withdrawal.destinationFacilityId,
        )
      }
    } else if (withdrawal.destinationFacilityId != null) {
      throw IllegalArgumentException("Only nursery transfers may include destination facility ID")
    }
    return dslContext.transactionResult { _ ->
      if (withdrawal.scheduledPlantingDateRequestId != null) {
        val scheduledPlantingDateRequestId = withdrawal.scheduledPlantingDateRequestId
        dslContext
            .selectOne()
            .from(SCHEDULED_PLANTING_DATES)
            .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateRequestId))
            .forUpdate()
            .fetchOne()
            ?: throw PlantingSeasonScheduledDateNotFoundException(scheduledPlantingDateRequestId)

        val requestStatus =
            dslContext
                .select(PLANTING_DATE_REQUESTS.STATUS_ID)
                .from(PLANTING_DATE_REQUESTS)
                .where(
                    PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(
                        scheduledPlantingDateRequestId
                    )
                )
                .fetchOne(PLANTING_DATE_REQUESTS.STATUS_ID)
                ?: throw PlantingSeasonDateRequestNotFoundException(scheduledPlantingDateRequestId)

        if (
            requestStatus == PlantingDateRequestStatus.Fulfilled &&
                withdrawal.purpose != WithdrawalPurpose.Undo
        ) {
          throw PlantingSeasonDateRequestFulfilledException(scheduledPlantingDateRequestId)
        }
      }

      val withdrawalsRow =
          WithdrawalsRow(
              createdBy = currentUser().userId,
              createdTime = clock.instant(),
              destinationFacilityId = withdrawal.destinationFacilityId,
              facilityId = withdrawal.facilityId,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
              notes = withdrawal.notes,
              plantingSeasonId = withdrawal.plantingSeasonId,
              purposeId = withdrawal.purpose,
              scheduledPlantingDateRequestId = withdrawal.scheduledPlantingDateRequestId,
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
                        readyQuantityWithdrawn = batchWithdrawal.readyQuantityWithdrawn,
                    )
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

                    if (
                        newGerminatingQuantity < 0 ||
                            newActiveGrowthQuantity < 0 ||
                            newHardeningOffQuantity < 0 ||
                            newReadyQuantity < 0
                    ) {
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
                        withdrawalId,
                        userSuppliedDate = withdrawal.withdrawnDate,
                    )
                  } else {
                    updateVersionedBatch(batchId, batch.version) {}
                  }
                }

                batchWithdrawalsRow
              }

      if (withdrawal.scheduledPlantingDateRequestId != null) {
        eventPublisher.publishEvent(
            WithdrawalAssociatedWithPlantingDateRequestEvent(
                isUndo = withdrawal.purpose == WithdrawalPurpose.Undo,
                scheduledPlantingDateRequestId = withdrawal.scheduledPlantingDateRequestId,
            )
        )
      }

      withdrawalsRow.plantingSeasonId?.let { plantingSeasonId ->
        val (plantingSiteId, organizationId) =
            seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)

        eventPublisher.publishEvent(
            PlantingSeasonWithdrawalCreatedEvent(
                facilityId = withdrawal.facilityId,
                organizationId = organizationId,
                plantingSeasonId = plantingSeasonId,
                plantingSiteId = plantingSiteId,
                withdrawalDate = withdrawal.withdrawnDate,
                withdrawalId = withdrawalId,
            )
        )
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
            plantingSeasonId = withdrawalToUndo.plantingSeasonId,
            purpose = WithdrawalPurpose.Undo,
            scheduledPlantingDateRequestId = withdrawalToUndo.scheduledPlantingDateRequestId,
            withdrawnDate = todayAtNursery,
            undoesWithdrawalId = withdrawalId,
        ),
        updateQuantityIfObservedBefore = withdrawalToUndo.withdrawnDate,
    )
  }

  /**
   * Tries to update a batch, retrying a few times if the update failed because the version number
   * was stale due to a concurrent update.
   */
  private fun <T> retryVersionedBatchUpdate(
      batchId: BatchId,
      updateFunc: (ExistingBatchModel) -> T,
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
      readyByDate: LocalDate?,
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
        val destinationQuantities = fetchQuantities(destinationBatch.id!!)
        updateQuantities(
            destinationBatch.id!!,
            destinationBatch.version!!,
            destinationQuantities.getValue(BatchStage.Germinating).quantity +
                batchWithdrawal.germinatingQuantityWithdrawn,
            destinationQuantities.getValue(BatchStage.ActiveGrowth).quantity +
                batchWithdrawal.activeGrowthQuantityWithdrawn,
            destinationQuantities.getValue(BatchStage.HardeningOff).quantity +
                batchWithdrawal.hardeningOffQuantityWithdrawn,
            destinationQuantities.getValue(BatchStage.Ready).quantity +
                batchWithdrawal.readyQuantityWithdrawn,
            BatchQuantityHistoryType.Computed,
            userSuppliedDate = withdrawal.withdrawnDate,
            withdrawalId = withdrawalId,
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
                    projectId = sourceBatch.projectId,
                    germinatingQuantity = batchWithdrawal.germinatingQuantityWithdrawn,
                    hardeningOffQuantity = batchWithdrawal.hardeningOffQuantityWithdrawn,
                    initialBatchId = sourceBatch.id,
                    activeGrowthQuantity = batchWithdrawal.activeGrowthQuantityWithdrawn,
                    readyByDate = readyByDate,
                    readyQuantity = batchWithdrawal.readyQuantityWithdrawn,
                    speciesId = sourceBatch.speciesId,
                ),
                withdrawalId,
            )

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
      setFunc: Batches.(UpdateSetMoreStep<BatchesRecord>) -> Unit,
  ) {
    dslContext.transaction { _ ->
      val rowsUpdated =
          dslContext
              .update(BATCHES)
              .set(BATCHES.MODIFIED_BY, currentUser().userId)
              .set(BATCHES.MODIFIED_TIME, clock.instant())
              .set(BATCHES.VERSION, version + 1)
              .apply { BATCHES.setFunc(this) }
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
              "BUG! Update of batch $batchId version $version touched 0 rows but version is correct"
          )
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
      notes: String? = null,
      accessionId: AccessionId? = null,
      userSuppliedDate: LocalDate? = null,
  ) {
    val recorded = clock.instant()
    val effectiveTime =
        if (userSuppliedDate == null) {
          recorded
        } else {
          val zone = parentStore.getEffectiveTimeZone(batchId)
          val previous =
              if (userSuppliedDate == LocalDate.ofInstant(recorded, zone)) {
                null
              } else {
                dslContext
                    .select(
                        BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME,
                        BATCH_QUANTITY_HISTORY.CREATED_TIME,
                    )
                    .from(BATCH_QUANTITY_HISTORY)
                    .where(BATCH_QUANTITY_HISTORY.BATCH_ID.eq(batchId))
                    .orderBy(BATCH_QUANTITY_HISTORY.VERSION.desc())
                    .limit(1)
                    .fetchOne()
                    ?.let { it.value1() ?: it.value2() }
              }
          computeEffectiveTime(
              previous = previous,
              recorded = recorded,
              userSuppliedDate = userSuppliedDate,
              zone = zone,
          )
        }

    batchQuantityHistoryDao.insert(
        BatchQuantityHistoryRow(
            accessionId = accessionId,
            activeGrowthQuantity = activeGrowthQuantity,
            batchId = batchId,
            createdBy = currentUser().userId,
            createdTime = recorded,
            effectiveTime = effectiveTime,
            germinatingQuantity = germinatingQuantity,
            hardeningOffQuantity = hardeningOffQuantity,
            historyTypeId = historyType,
            notes = notes,
            readyQuantity = readyQuantity,
            version = version,
            withdrawalId = withdrawalId,
        ),
    )
  }

  /**
   * Returns when a quantity change took effect. This is needed by the time-in-stage calculations.
   * The effective time can be earlier than the recorded time if a user backdated a withdrawal, a
   * transfer, or seeds added from an accession. A user-supplied date means the start of that day in
   * the nursery's time zone, clamped to no earlier than the previous history row and no later than
   * the recorded time.
   */
  private fun computeEffectiveTime(
      previous: Instant?,
      recorded: Instant,
      userSuppliedDate: LocalDate,
      zone: ZoneId,
  ): Instant {
    if (userSuppliedDate == LocalDate.ofInstant(recorded, zone)) return recorded

    val userTime = userSuppliedDate.atStartOfDay(zone).toInstant()
    return minOf(recorded, previous?.let { maxOf(userTime, it) } ?: userTime)
  }

  fun fetchEstimatedReady(
      facilityId: FacilityId,
      after: LocalDate,
      until: LocalDate,
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
                it[ID]!!,
                it[BATCH_NUMBER]!!,
                it[SPECIES_ID]!!,
                it[FACILITIES.NAME]!!,
            )
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
                            BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN
                        )
                    )
                )
            )
        )
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
                    .andExists(
                        DSL.selectOne()
                            .from(BATCH_STAGE_DETAILS)
                            .where(BATCH_STAGE_DETAILS.BATCH_ID.eq(BATCHES.ID))
                            .and(BATCH_STAGE_DETAILS.QUANTITY.gt(0))
                    )
            )
        )
        .orderBy(SPECIES.ID)
        .fetchInto(SpeciesRow::class.java)
  }

  fun getNurseryStats(
      facilityId: FacilityId? = null,
      projectId: ProjectId? = null,
      organizationId: OrganizationId? = null,
  ): NurseryStats {
    if (facilityId == null && projectId == null && organizationId == null) {
      throw IllegalArgumentException(
          "Stats must be calculated by facility, project, or organization"
      )
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
                    BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN
                )
            )
        )
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

    val hasInventory =
        DSL.exists(
            DSL.selectOne()
                .from(BATCH_STAGE_DETAILS)
                .where(BATCH_STAGE_DETAILS.BATCH_ID.eq(BATCHES.ID))
                .and(BATCH_STAGE_DETAILS.QUANTITY.gt(0))
        )
    val batchTotals =
        dslContext
            .select(
                aggregateGerminationRateField,
                aggregateLossRateField,
                DSL.count().filterWhere(hasInventory),
                DSL.countDistinct(BATCHES.SPECIES_ID).filterWhere(hasInventory),
            )
            .from(BATCHES)
            .where(conditions)
            .fetchSingle()
    val inventoryTotals =
        dslContext
            .select(
                DSL.sum(BATCH_STAGE_DETAILS.QUANTITY)
                    .filterWhere(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.Germinating)),
                DSL.sum(BATCH_STAGE_DETAILS.QUANTITY)
                    .filterWhere(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.ActiveGrowth)),
                DSL.sum(BATCH_STAGE_DETAILS.QUANTITY)
                    .filterWhere(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.HardeningOff)),
                DSL.sum(BATCH_STAGE_DETAILS.QUANTITY)
                    .filterWhere(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.Ready)),
            )
            .from(BATCH_STAGE_DETAILS)
            .join(BATCHES)
            .on(BATCHES.ID.eq(BATCH_STAGE_DETAILS.BATCH_ID))
            .where(conditions)
            .fetchSingle()

    return NurseryStats(
        facilityId = facilityId,
        germinationRate = batchTotals.value1()?.roundToInt(),
        lossRate = batchTotals.value2()?.roundToInt(),
        batchesWithInventory = batchTotals.value3() ?: 0,
        speciesWithInventory = batchTotals.value4() ?: 0,
        totalGerminating = inventoryTotals.value1()?.toLong() ?: 0L,
        totalActiveGrowth = inventoryTotals.value2()?.toLong() ?: 0L,
        totalHardeningOff = inventoryTotals.value3()?.toLong() ?: 0L,
        totalReady = inventoryTotals.value4()?.toLong() ?: 0L,
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

  private fun fetchAccessionNumbers(batchId: BatchId): Map<AccessionId, String> {
    return dslContext
        .select(ACCESSIONS.ID.asNonNullable(), ACCESSIONS.NUMBER.asNonNullable())
        .from(BATCH_QUANTITY_HISTORY)
        .join(ACCESSIONS)
        .on(BATCH_QUANTITY_HISTORY.ACCESSION_ID.eq(ACCESSIONS.ID))
        .where(BATCH_QUANTITY_HISTORY.BATCH_ID.eq(batchId))
        .orderBy(BATCH_QUANTITY_HISTORY.ID)
        .fetch()
        .distinct()
        .associate { it.value1() to it.value2() }
  }

  private fun updateSubLocations(
      batchId: BatchId,
      facilityId: FacilityId,
      existingSubLocationIds: Set<SubLocationId>,
      desiredSubLocationIds: Set<SubLocationId>,
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

  private data class TimeInStageHistoryRow(
      val effectiveTime: Instant?,
      val germinating: Int,
      val activeGrowth: Int,
      val hardeningOff: Int,
      val ready: Int,
      val hasUndoRow: Boolean,
      val historyType: BatchQuantityHistoryType,
      val netGerminatingQuantity: Int?,
      val netActiveGrowthQuantity: Int?,
      val netHardeningOffQuantity: Int?,
      val netReadyQuantity: Int?,
      val originatingBatchId: BatchId?,
      val purpose: WithdrawalPurpose?,
      val version: Int,
      val withdrawalId: WithdrawalId?,
  )

  /**
   * Returns a batch's quantity history in version order, along with the withdrawal details needed
   * to tell what kind of change each row represents.
   */
  private fun fetchTimeInStageHistory(batchId: BatchId): List<TimeInStageHistoryRow> {
    // Net per-stage change from each withdrawal, including transfers into this batch. Used by
    // isReplayable to detect gaps in the batch's history.
    val transfer = BATCH_WITHDRAWALS.`as`("history_transfer")
    fun netQuantity(alias: String, quantity: Field<Int?>) =
        DSL.sum(
                DSL.case_()
                    .`when`(transfer.DESTINATION_BATCH_ID.eq(batchId), quantity)
                    .`when`(transfer.BATCH_ID.eq(batchId), quantity.neg())
                    .otherwise(0)
            )
            .cast(SQLDataType.INTEGER)
            .`as`(alias)

    val netGerminatingDelta =
        netQuantity("net_germinating_quantity", transfer.GERMINATING_QUANTITY_WITHDRAWN)
    val netActiveGrowthDelta =
        netQuantity("net_active_growth_quantity", transfer.ACTIVE_GROWTH_QUANTITY_WITHDRAWN)
    val netHardeningOffDelta =
        netQuantity("net_hardening_off_quantity", transfer.HARDENING_OFF_QUANTITY_WITHDRAWN)
    val netReadyDelta = netQuantity("net_ready_quantity", transfer.READY_QUANTITY_WITHDRAWN)
    val transferDeltas =
        DSL.select(
                transfer.WITHDRAWAL_ID,
                netGerminatingDelta,
                netActiveGrowthDelta,
                netHardeningOffDelta,
                netReadyDelta,
            )
            .from(transfer)
            .where(transfer.BATCH_ID.eq(batchId).or(transfer.DESTINATION_BATCH_ID.eq(batchId)))
            .groupBy(transfer.WITHDRAWAL_ID)
            .asTable("transfer_deltas")
    val transferWithdrawalId = transferDeltas.field(transfer.WITHDRAWAL_ID)!!
    val netGerminatingQuantity = transferDeltas.field(netGerminatingDelta)!!
    val netActiveGrowthQuantity = transferDeltas.field(netActiveGrowthDelta)!!
    val netHardeningOffQuantity = transferDeltas.field(netHardeningOffDelta)!!
    val netReadyQuantity = transferDeltas.field(netReadyDelta)!!

    val undoHistory = BATCH_QUANTITY_HISTORY.`as`("undo_history")
    val undoWithdrawal = WITHDRAWALS.`as`("undo_withdrawal")
    val hasUndoRow =
        DSL.field(
            DSL.exists(
                dslContext
                    .selectOne()
                    .from(undoHistory)
                    .join(undoWithdrawal)
                    .on(undoHistory.WITHDRAWAL_ID.eq(undoWithdrawal.ID))
                    .where(undoHistory.BATCH_ID.eq(batchId))
                    .and(
                        undoWithdrawal.UNDOES_WITHDRAWAL_ID.eq(BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID)
                    )
            )
        )

    return dslContext
        .select(
            BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY,
            BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME,
            BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY,
            BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY,
            BATCH_QUANTITY_HISTORY.HISTORY_TYPE_ID,
            BATCH_QUANTITY_HISTORY.READY_QUANTITY,
            BATCH_QUANTITY_HISTORY.VERSION,
            BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID,
            BATCH_WITHDRAWALS.BATCH_ID,
            netGerminatingQuantity,
            netActiveGrowthQuantity,
            netHardeningOffQuantity,
            netReadyQuantity,
            WITHDRAWALS.PURPOSE_ID,
            hasUndoRow,
        )
        .from(BATCH_QUANTITY_HISTORY)
        .leftJoin(BATCH_WITHDRAWALS)
        .on(BATCH_WITHDRAWALS.BATCH_ID.eq(BATCH_QUANTITY_HISTORY.BATCH_ID))
        .and(BATCH_WITHDRAWALS.WITHDRAWAL_ID.eq(BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID))
        .leftJoin(transferDeltas)
        .on(transferWithdrawalId.eq(BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID))
        .leftJoin(WITHDRAWALS)
        .on(WITHDRAWALS.ID.eq(BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID))
        .where(BATCH_QUANTITY_HISTORY.BATCH_ID.eq(batchId))
        .orderBy(BATCH_QUANTITY_HISTORY.VERSION)
        .fetch { row ->
          TimeInStageHistoryRow(
              activeGrowth = row[BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY]!!,
              effectiveTime = row[BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME],
              germinating = row[BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY]!!,
              hardeningOff = row[BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY]!!,
              hasUndoRow = row[hasUndoRow] == true,
              historyType = row[BATCH_QUANTITY_HISTORY.HISTORY_TYPE_ID]!!,
              netActiveGrowthQuantity = row[netActiveGrowthQuantity],
              netGerminatingQuantity = row[netGerminatingQuantity],
              netHardeningOffQuantity = row[netHardeningOffQuantity],
              netReadyQuantity = row[netReadyQuantity],
              originatingBatchId = row[BATCH_WITHDRAWALS.BATCH_ID],
              purpose = row[WITHDRAWALS.PURPOSE_ID],
              ready = row[BATCH_QUANTITY_HISTORY.READY_QUANTITY]!!,
              version = row[BATCH_QUANTITY_HISTORY.VERSION]!!,
              withdrawalId = row[BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID],
          )
        }
  }

  /**
   * Returns true if a batch's quantity history is complete enough to calculate time in stage. It
   * isn't if the initial row is missing, if any row lacks an effective time, or if a withdrawal's
   * quantities don't account for the change from the previous row.
   */
  private fun isReplayable(historyRows: List<TimeInStageHistoryRow>): Boolean {
    if (historyRows.isEmpty()) return false

    val first = historyRows.first()
    return first.historyType == BatchQuantityHistoryType.Observed &&
        first.version == 1 &&
        historyRows.none { it.effectiveTime == null } &&
        historyRows.zipWithNext().all { (previous, row) ->
          row.historyType != BatchQuantityHistoryType.Computed ||
              row.withdrawalId == null ||
              row.netGerminatingQuantity == null ||
              row.netActiveGrowthQuantity == null ||
              row.netHardeningOffQuantity == null ||
              row.netReadyQuantity == null ||
              (previous.germinating + row.netGerminatingQuantity == row.germinating &&
                  previous.activeGrowth + row.netActiveGrowthQuantity == row.activeGrowth &&
                  previous.hardeningOff + row.netHardeningOffQuantity == row.hardeningOff &&
                  previous.ready + row.netReadyQuantity == row.ready)
        }
  }

  /**
   * Converts quantity history rows to time-in-stage calculator events, classifying each row by what
   * kind of change it represents.
   */
  private fun toStageTimeEvents(
      batchId: BatchId,
      historyRows: List<TimeInStageHistoryRow>,
  ): List<StageTimeEvent> = historyRows.mapIndexed { index, row ->
    val type =
        when {
          index == 0 -> StageTimeEventType.Created
          row.historyType == BatchQuantityHistoryType.StatusChanged ->
              StageTimeEventType.StageChange
          row.purpose == WithdrawalPurpose.Undo || row.hasUndoRow -> StageTimeEventType.Skipped
          row.historyType == BatchQuantityHistoryType.Observed -> StageTimeEventType.ManualEdit
          row.purpose == WithdrawalPurpose.OutPlant && row.originatingBatchId == batchId ->
              StageTimeEventType.Outplanting
          else -> StageTimeEventType.Other
        }

    StageTimeEvent(
        activeGrowth = row.activeGrowth,
        effectiveTime = row.effectiveTime!!,
        germinating = row.germinating,
        hardeningOff = row.hardeningOff,
        ready = row.ready,
        type = type,
    )
  }

  /** Stores calculated time-in-stage statistics in a batch's stage details. */
  private fun writeTimeInStage(batchId: BatchId, stats: Map<BatchStage, StageTimeStats>) {
    stats.forEach { (stage, stageStats) ->
      dslContext
          .update(BATCH_STAGE_DETAILS)
          .set(BATCH_STAGE_DETAILS.COUNTED_QUANTITY, stageStats.countedQuantity)
          .set(BATCH_STAGE_DETAILS.COUNTED_SEEDLING_DAYS, stageStats.countedDays)
          .set(BATCH_STAGE_DETAILS.FINISHED_QUANTITY, stageStats.finishedQuantity)
          .set(BATCH_STAGE_DETAILS.FINISHED_SEEDLING_DAYS, stageStats.finishedDays)
          .set(BATCH_STAGE_DETAILS.OPEN_SEEDLING_DAYS, stageStats.openDays)
          .set(BATCH_STAGE_DETAILS.TIME_IN_STAGE_CLEARED, stageStats.cleared)
          .set(BATCH_STAGE_DETAILS.TIME_IN_STAGE_AS_OF, stageStats.asOfTime)
          .where(BATCH_STAGE_DETAILS.BATCH_ID.eq(batchId))
          .and(BATCH_STAGE_DETAILS.STAGE_ID.eq(stage))
          .execute()
    }
  }

  private fun updateTimeInStage(batchId: BatchId) {
    val historyRows = fetchTimeInStageHistory(batchId)
    if (!isReplayable(historyRows)) return

    val first = historyRows.first()
    val batchDateRow =
        dslContext
            .select(
                BATCHES.CREATED_TIME,
                BATCHES.SEEDS_SOWN_DATE,
                BATCHES.GERMINATION_STARTED_DATE,
            )
            .from(BATCHES)
            .where(BATCHES.ID.eq(batchId))
            .fetchOne() ?: return
    val createdTime = batchDateRow[BATCHES.CREATED_TIME]!!
    val initialGerminatingStart =
        if (first.withdrawalId == null) {
          val earliestDate =
              listOfNotNull(
                      batchDateRow[BATCHES.SEEDS_SOWN_DATE],
                      batchDateRow[BATCHES.GERMINATION_STARTED_DATE],
                  )
                  .minOrNull()
          earliestDate?.atStartOfDay(parentStore.getEffectiveTimeZone(batchId))?.toInstant()
        } else {
          null
        }

    val events = toStageTimeEvents(batchId, historyRows)
    val stats =
        StageTimeCalculator.calculate(events, initialGerminatingStart).mapValues {
            (stage, calculatedStats) ->
          if (
              stage == BatchStage.ActiveGrowth && createdTime.isBefore(HARDENING_OFF_RELEASE_TIME)
          ) {
            calculatedStats.copy(
                cleared = false,
                countedDays = 0.0,
                countedQuantity = 0,
                finishedDays = 0.0,
                finishedQuantity = 0.0,
                openDays = 0.0,
            )
          } else {
            calculatedStats
          }
        }
    writeTimeInStage(batchId, stats)
  }

  fun fetchBatchIdsNeedingFinishedTimeInStage(): List<BatchId> =
      dslContext
          .selectDistinct(BATCH_STAGE_DETAILS.BATCH_ID)
          .from(BATCH_STAGE_DETAILS)
          .where(BATCH_STAGE_DETAILS.COUNTED_QUANTITY.isNotNull)
          .and(BATCH_STAGE_DETAILS.FINISHED_QUANTITY.isNull)
          .fetch(BATCH_STAGE_DETAILS.BATCH_ID.asNonNullable())

  fun recalculateTimeInStage(batchId: BatchId) {
    dslContext.transaction { _ ->
      dslContext
          .select(BATCHES.ID)
          .from(BATCHES)
          .where(BATCHES.ID.eq(batchId))
          .forUpdate()
          .fetchOne() ?: return@transaction

      updateTimeInStage(batchId)
    }
  }

  /**
   * Recomputes effective times from the first missing value onward, then calculates the batch's
   * time-in-stage stats. Withdrawals and transfers use their withdrawal dates. Legacy rows for
   * seeds added to an existing batch from an accession don't link to the seed bank withdrawal, so
   * they use the date of the only matching withdrawal recorded within a minute of the row, if there
   * is one. Other rows use their recorded times unless a stored effective time is available.
   * Batches whose history isn't replayable still get no stats.
   */
  fun backfillTimeInStage(batchId: BatchId) {
    dslContext.transaction { _ ->
      dslContext
          .select(BATCHES.ID)
          .from(BATCHES)
          .where(BATCHES.ID.eq(batchId))
          .forUpdate()
          .fetchOne() ?: throw BatchNotFoundException(batchId)

      val historyRows =
          dslContext
              .select(
                  BATCH_QUANTITY_HISTORY.ACCESSION_ID,
                  BATCH_QUANTITY_HISTORY.CREATED_TIME,
                  BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME,
                  BATCH_QUANTITY_HISTORY.ID,
                  BATCH_QUANTITY_HISTORY.VERSION,
                  BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID,
                  WITHDRAWALS.WITHDRAWN_DATE,
              )
              .from(BATCH_QUANTITY_HISTORY)
              .leftJoin(WITHDRAWALS)
              .on(WITHDRAWALS.ID.eq(BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID))
              .where(BATCH_QUANTITY_HISTORY.BATCH_ID.eq(batchId))
              .orderBy(BATCH_QUANTITY_HISTORY.VERSION)
              .fetch()
      if (historyRows.none { it[BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME] == null }) {
        return@transaction
      }

      val zone by lazy { parentStore.getEffectiveTimeZone(batchId) }
      var previous: Instant? = null
      var recompute = false
      historyRows.forEach { row ->
        val storedEffectiveTime = row[BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME]
        if (storedEffectiveTime == null) recompute = true
        val effectiveTime =
            if (recompute) {
              val createdTime = row[BATCH_QUANTITY_HISTORY.CREATED_TIME]!!
              val accessionId = row[BATCH_QUANTITY_HISTORY.ACCESSION_ID]
              val withdrawalId = row[BATCH_QUANTITY_HISTORY.WITHDRAWAL_ID]
              val userSuppliedDate =
                  when {
                    withdrawalId != null -> row[WITHDRAWALS.WITHDRAWN_DATE]
                    accessionId != null && row[BATCH_QUANTITY_HISTORY.VERSION]!! > 1 -> {
                      // Seeds added to an existing batch from an accession use the accession
                      // withdrawal's date. Legacy history rows don't link to that withdrawal,
                      // so look for the only one created within a minute of the history row.
                      val createdTimeStart = createdTime.minusSeconds(60)
                      val createdTimeEnd = createdTime.plusSeconds(60)
                      dslContext
                          .select(SEEDBANK_WITHDRAWALS.DATE)
                          .from(SEEDBANK_WITHDRAWALS)
                          .where(SEEDBANK_WITHDRAWALS.BATCH_ID.eq(batchId))
                          .and(SEEDBANK_WITHDRAWALS.ACCESSION_ID.eq(accessionId))
                          .and(
                              SEEDBANK_WITHDRAWALS.PURPOSE_ID.eq(SeedbankWithdrawalPurpose.Nursery)
                          )
                          .and(
                              SEEDBANK_WITHDRAWALS.CREATED_TIME.between(
                                  createdTimeStart,
                                  createdTimeEnd,
                              )
                          )
                          .fetch(SEEDBANK_WITHDRAWALS.DATE)
                          .singleOrNull()
                    }
                    else -> null
                  }
              val effectiveTime =
                  if (userSuppliedDate != null) {
                    computeEffectiveTime(
                        previous = previous,
                        recorded = createdTime,
                        userSuppliedDate = userSuppliedDate,
                        zone = zone,
                    )
                  } else {
                    storedEffectiveTime ?: createdTime
                  }
              dslContext
                  .update(BATCH_QUANTITY_HISTORY)
                  .set(BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME, effectiveTime)
                  .where(BATCH_QUANTITY_HISTORY.ID.eq(row[BATCH_QUANTITY_HISTORY.ID]!!))
                  .execute()
              effectiveTime
            } else {
              storedEffectiveTime!!
            }
        previous = effectiveTime
      }

      updateTimeInStage(batchId)
    }
  }

  /**
   * Updates the germination and loss rates for a batch. This does not count as a new version of the
   * batch. When this is called as part of a quantity update, the quantity history row for the new
   * update should be inserted before this is called.
   */
  fun updateRates(batchId: BatchId) {
    val undoneWithdrawals = WITHDRAWALS.`as`("undone_withdrawals")
    val undonePurposeId = undoneWithdrawals.PURPOSE_ID.`as`("undone_purpose_id")

    val quantityHistory =
        dslContext
            .select(
                BATCH_QUANTITY_HISTORY.ACCESSION_ID,
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

    var initialGerminating = initialEvent[BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY]!!
    var initialActiveGrowth = initialEvent[BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY]!!
    var initialHardeningOff = initialEvent[BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY]!!
    var initialReady = initialEvent[BATCH_QUANTITY_HISTORY.READY_QUANTITY]!!

    var hasManualGerminatingEdit = false
    var hasManualHardeningOffEdit = false
    var hasManualActiveGrowthEdit = false
    var hasManualReadyEdit = false
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

      // Count incoming transfers as if they were part of the initial quantities.
      if (
          current[BATCH_QUANTITY_HISTORY.ACCESSION_ID] != null ||
              (purpose == WithdrawalPurpose.NurseryTransfer &&
                  current[BATCH_WITHDRAWALS.BATCH_ID] != batchId)
      ) {
        fun differenceFromPrevious(field: Field<Int?>): Int = current[field]!! - previous[field]!!

        initialGerminating += differenceFromPrevious(BATCH_QUANTITY_HISTORY.GERMINATING_QUANTITY)
        initialHardeningOff += differenceFromPrevious(BATCH_QUANTITY_HISTORY.HARDENING_OFF_QUANTITY)
        initialActiveGrowth += differenceFromPrevious(BATCH_QUANTITY_HISTORY.ACTIVE_GROWTH_QUANTITY)
        initialReady += differenceFromPrevious(BATCH_QUANTITY_HISTORY.READY_QUANTITY)
      }

      if (
          purpose == WithdrawalPurpose.OutPlant ||
              purpose == WithdrawalPurpose.Dead ||
              undonePurpose == WithdrawalPurpose.OutPlant ||
              undonePurpose == WithdrawalPurpose.Dead
      ) {
        totalOutplantAndDeadNonGerminating +=
            activeGrowthQuantityWithdrawn + hardeningOffQuantityWithdrawn + readyQuantityWithdrawn
      }

      if (purpose == WithdrawalPurpose.Dead || undonePurpose == WithdrawalPurpose.Dead) {
        totalDeadNonGerminating +=
            activeGrowthQuantityWithdrawn + hardeningOffQuantityWithdrawn + readyQuantityWithdrawn
      }

      if (
          purpose != null &&
              purpose != WithdrawalPurpose.Dead &&
              undonePurpose != WithdrawalPurpose.Dead
      ) {
        totalNonDeadGerminating += germinatingQuantityWithdrawn
      }

      totalWithdrawnNonGerminating +=
          activeGrowthQuantityWithdrawn + hardeningOffQuantityWithdrawn + readyQuantityWithdrawn

      current
    }

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
        if (
            totalGerminationCandidates > 0 &&
                currentGerminating == 0 &&
                !hasManualGerminatingEdit &&
                !hasManualActiveGrowthEdit &&
                !hasManualHardeningOffEdit &&
                !hasManualReadyEdit
        ) {
          (100.0 * totalGerminated / totalGerminationCandidates).roundToInt()
        } else {
          null
        }

    val totalLost = totalDeadNonGerminating
    val totalLossCandidates = totalOutplantAndDeadNonGerminating + currentNonGerminating
    val lossRate: Int? =
        if (
            totalLossCandidates > 0 &&
                !hasManualActiveGrowthEdit &&
                !hasManualHardeningOffEdit &&
                !hasManualReadyEdit
        ) {
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
            germinationRate?.let { totalGerminationCandidates },
        )
        .set(BATCHES.LOSS_RATE, lossRate)
        .set(BATCHES.TOTAL_LOST, lossRate?.let { totalLost })
        .set(BATCHES.TOTAL_LOSS_CANDIDATES, lossRate?.let { totalLossCandidates })
        .where(BATCHES.ID.eq(batchId))
        .execute()
  }

  private fun writeStageQuantities(
      batchId: BatchId,
      germinating: Int,
      activeGrowth: Int,
      hardeningOff: Int,
      ready: Int,
      observed: Boolean,
  ) {
    mapOf(
            BatchStage.Germinating to germinating,
            BatchStage.ActiveGrowth to activeGrowth,
            BatchStage.HardeningOff to hardeningOff,
            BatchStage.Ready to ready,
        )
        .forEach { (stage, quantity) ->
          with(BATCH_STAGE_DETAILS) {
            dslContext
                .insertInto(this)
                .set(BATCH_ID, batchId)
                .set(STAGE_ID, stage)
                .set(QUANTITY, quantity)
                .set(LATEST_OBSERVED_QUANTITY, quantity)
                .onConflict(BATCH_ID, STAGE_ID)
                .doUpdate()
                .set(QUANTITY, quantity)
                .apply {
                  if (observed) {
                    set(LATEST_OBSERVED_QUANTITY, quantity)
                  }
                }
                .execute()
          }
        }
  }
}
