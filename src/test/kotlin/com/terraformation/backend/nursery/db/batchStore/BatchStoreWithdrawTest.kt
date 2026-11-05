package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchStageDetailsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.nursery.tables.records.BatchQuantityHistoryRecord
import com.terraformation.backend.db.nursery.tables.records.BatchStageDetailsRecord
import com.terraformation.backend.db.nursery.tables.records.WithdrawalsRecord
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.db.nursery.tables.references.BATCH_STAGE_DETAILS
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.nursery.db.BatchInventoryInsufficientException
import com.terraformation.backend.nursery.db.CrossOrganizationNurseryTransferNotAllowedException
import com.terraformation.backend.nursery.event.WithdrawalAssociatedWithPlantingDateRequestEvent
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonDateRequestFulfilledException
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonDateRequestNotFoundException
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonScheduledDateNotFoundException
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class BatchStoreWithdrawTest : BatchStoreTest() {
  private lateinit var speciesId2: SpeciesId
  private lateinit var species1Batch1Id: BatchId
  private lateinit var species1Batch2Id: BatchId
  private lateinit var species2Batch1Id: BatchId

  @BeforeEach
  fun insertInitialBatches() {
    speciesId2 = insertSpecies()
    species1Batch1Id =
        insertBatch(
            speciesId = speciesId,
            batchNumber = "21-2-1-011",
            germinatingQuantity = 10,
            activeGrowthQuantity = 20,
            readyQuantity = 30,
            hardeningOffQuantity = 40,
            totalLost = 0,
            totalLossCandidates = 20 + 30 + 40,
        )
    species1Batch2Id =
        insertBatch(
            speciesId = speciesId,
            batchNumber = "21-2-1-012",
            germinatingQuantity = 40,
            activeGrowthQuantity = 50,
            readyQuantity = 60,
            hardeningOffQuantity = 70,
            totalLost = 0,
            totalLossCandidates = 50 + 60 + 70,
        )
    species2Batch1Id =
        insertBatch(
            speciesId = speciesId2,
            batchNumber = "21-2-1-021",
            germinatingQuantity = 70,
            activeGrowthQuantity = 80,
            readyQuantity = 90,
            hardeningOffQuantity = 100,
            totalLost = 0,
            totalLossCandidates = 80 + 90 + 100,
        )
  }

  @Test
  fun `can withdraw from multiple batches`() {
    val expectedQuantity = expectedQuantitiesFunc()

    val species1Batch1 = batchesDao.fetchOneById(species1Batch1Id)!!
    val species1Batch2 = batchesDao.fetchOneById(species1Batch2Id)!!
    val species2Batch1 = batchesDao.fetchOneById(species2Batch1Id)!!

    val withdrawalTime = clock.instant().plusSeconds(1000)
    clock.instant = withdrawalTime

    val withdrawal =
        store.withdraw(
            NewWithdrawalModel(
                facilityId = facilityId,
                id = null,
                notes = "Notes",
                purpose = WithdrawalPurpose.Other,
                withdrawnDate = LocalDate.EPOCH,
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 1,
                            activeGrowthQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4,
                        ),
                        BatchWithdrawalModel(
                            batchId = species1Batch2Id,
                            germinatingQuantityWithdrawn = 4,
                            activeGrowthQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6,
                            hardeningOffQuantityWithdrawn = 7,
                        ),
                        BatchWithdrawalModel(
                            batchId = species2Batch1Id,
                            germinatingQuantityWithdrawn = 7,
                            activeGrowthQuantityWithdrawn = 8,
                            readyQuantityWithdrawn = 9,
                            hardeningOffQuantityWithdrawn = 10,
                        ),
                    ),
            )
        )

    assertAll(
        {
          assertTableEquals(
              listOf(
                  expectedQuantity(species1Batch1, BatchStage.Germinating, 10 - 1),
                  expectedQuantity(species1Batch1, BatchStage.ActiveGrowth, 20 - 2),
                  expectedQuantity(species1Batch1, BatchStage.HardeningOff, 40 - 4),
                  expectedQuantity(species1Batch1, BatchStage.Ready, 30 - 3),
                  expectedQuantity(species1Batch2, BatchStage.Germinating, 40 - 4),
                  expectedQuantity(species1Batch2, BatchStage.ActiveGrowth, 50 - 5),
                  expectedQuantity(species1Batch2, BatchStage.HardeningOff, 70 - 7),
                  expectedQuantity(species1Batch2, BatchStage.Ready, 60 - 6),
                  expectedQuantity(species2Batch1, BatchStage.Germinating, 70 - 7),
                  expectedQuantity(species2Batch1, BatchStage.ActiveGrowth, 80 - 8),
                  expectedQuantity(species2Batch1, BatchStage.HardeningOff, 100 - 10),
                  expectedQuantity(species2Batch1, BatchStage.Ready, 90 - 9),
              )
          )
        },
        {
          assertEquals(
              listOf(
                  species1Batch1.copy(
                      totalLossCandidates = 20 + 30 + 40 - 2 - 3 - 4,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species1Batch2.copy(
                      totalLossCandidates = 50 + 60 + 70 - 5 - 6 - 7,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species2Batch1.copy(
                      totalLossCandidates = 80 + 90 + 100 - 8 - 9 - 10,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
              ),
              batchesDao.findAll().sortedBy { it.id },
              "Should have deducted withdrawn quantities from batches",
          )
        },
        {
          val newHistoryRow =
              BatchQuantityHistoryRow(
                  historyTypeId = BatchQuantityHistoryType.Computed,
                  createdBy = user.userId,
                  createdTime = withdrawalTime,
                  withdrawalId = withdrawal.id,
                  version = 2,
                  effectiveTime = withdrawalTime,
              )

          assertEquals(
              listOf(
                  newHistoryRow.copy(
                      batchId = species1Batch1Id,
                      germinatingQuantity = 9,
                      activeGrowthQuantity = 18,
                      readyQuantity = 27,
                      hardeningOffQuantity = 36,
                  ),
                  newHistoryRow.copy(
                      batchId = species1Batch2Id,
                      germinatingQuantity = 36,
                      activeGrowthQuantity = 45,
                      readyQuantity = 54,
                      hardeningOffQuantity = 63,
                  ),
                  newHistoryRow.copy(
                      batchId = species2Batch1Id,
                      germinatingQuantity = 63,
                      activeGrowthQuantity = 72,
                      readyQuantity = 81,
                      hardeningOffQuantity = 90,
                  ),
              ),
              batchQuantityHistoryDao
                  .findAll()
                  .map { it.copy(id = null) }
                  .sortedBy { it.germinatingQuantity!! },
              "Should have inserted quantity history rows",
          )
        },
        {
          assertEquals(
              listOf(
                  WithdrawalsRow(
                      id = withdrawal.id,
                      facilityId = facilityId,
                      notes = "Notes",
                      purposeId = WithdrawalPurpose.Other,
                      withdrawnDate = LocalDate.EPOCH,
                      createdBy = user.userId,
                      createdTime = withdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = withdrawalTime,
                  )
              ),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row",
          )
        },
    )
  }

  @Test
  fun `does not update batch quantities if withdrawal date is earlier than latest observation`() {
    val species1Batch1 =
        batchesDao
            .fetchOneById(species1Batch1Id)!!
            .copy(latestObservedTime = Instant.EPOCH.plus(3, ChronoUnit.DAYS))

    batchesDao.update(species1Batch1)

    val withdrawalTime = Instant.EPOCH.plus(4, ChronoUnit.DAYS)
    clock.instant = withdrawalTime

    val withdrawal =
        store.withdraw(
            NewWithdrawalModel(
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.Other,
                withdrawnDate = LocalDate.EPOCH,
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 100000,
                            activeGrowthQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4,
                        )
                    ),
            )
        )

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(version = 2, modifiedTime = withdrawalTime),
              batchesDao.fetchOneById(species1Batch1Id),
              "Should not have deducted withdrawn quantities from batch",
          )
        },
        {
          assertTableEmpty(BATCH_QUANTITY_HISTORY, "Should not have inserted quantity history row")
        },
        {
          assertEquals(
              listOf(
                  WithdrawalsRow(
                      id = withdrawal.id,
                      facilityId = facilityId,
                      purposeId = WithdrawalPurpose.Other,
                      withdrawnDate = LocalDate.EPOCH,
                      createdBy = user.userId,
                      createdTime = withdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = withdrawalTime,
                  )
              ),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row",
          )
        },
        {
          assertEquals(
              listOf(
                  BatchWithdrawalsRow(
                      batchId = species1Batch1Id,
                      withdrawalId = withdrawal.id,
                      germinatingQuantityWithdrawn = 100000,
                      activeGrowthQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3,
                      hardeningOffQuantityWithdrawn = 4,
                  )
              ),
              batchWithdrawalsDao.findAll(),
              "Should have inserted batch withdrawals row",
          )
        },
    )
  }

  @Test
  fun `uses nursery time zone to determine date of last observation`() {
    val expectedQuantity = expectedQuantitiesFunc()

    // Last observation is 2023-01-02 02:00 UTC, which is January 1 in New York; a withdrawal
    // with a date of January 1 should not count as happening before the observation.
    val latestObservedTime = ZonedDateTime.of(2023, 1, 2, 2, 0, 0, 0, ZoneOffset.UTC).toInstant()
    val withdrawnDate = LocalDate.of(2023, 1, 1)

    val newYorkZone = ZoneId.of("America/New_York")
    facilitiesDao.update(facilitiesDao.fetchOneById(facilityId)!!.copy(timeZone = newYorkZone))

    val species1Batch1 =
        batchesDao.fetchOneById(species1Batch1Id)!!.copy(latestObservedTime = latestObservedTime)

    batchesDao.update(species1Batch1)

    val withdrawalTime = latestObservedTime.plus(4, ChronoUnit.DAYS)
    clock.instant = withdrawalTime

    val withdrawal =
        store.withdraw(
            NewWithdrawalModel(
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.Other,
                withdrawnDate = withdrawnDate,
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 1,
                            activeGrowthQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4,
                        )
                    ),
            )
        )

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(
                  version = 2,
                  modifiedTime = withdrawalTime,
                  totalLossCandidates = 20 + 30 + 40 - 2 - 3 - 4,
              ),
              batchesDao.fetchOneById(species1Batch1Id),
              "Should have deducted withdrawn quantities from batch",
          )
          assertTableEquals(
              listOf(
                  expectedQuantity(species1Batch1, BatchStage.Germinating, 10 - 1),
                  expectedQuantity(species1Batch1, BatchStage.ActiveGrowth, 20 - 2),
                  expectedQuantity(species1Batch1, BatchStage.HardeningOff, 40 - 4),
                  expectedQuantity(species1Batch1, BatchStage.Ready, 30 - 3),
              ),
              where = BATCH_STAGE_DETAILS.BATCH_ID.eq(species1Batch1.id!!),
          )
        },
        {
          assertEquals(
              listOf(
                  BatchQuantityHistoryRow(
                      batchId = species1Batch1Id,
                      withdrawalId = withdrawal.id,
                      historyTypeId = BatchQuantityHistoryType.Computed,
                      createdBy = user.userId,
                      createdTime = withdrawalTime,
                      germinatingQuantity = 9,
                      activeGrowthQuantity = 18,
                      readyQuantity = 27,
                      hardeningOffQuantity = 36,
                      version = 2,
                      effectiveTime =
                          ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, newYorkZone).toInstant(),
                  ),
              ),
              batchQuantityHistoryDao.findAll().map { it.copy(id = null) },
              "Should have inserted quantity history row",
          )
        },
        {
          assertEquals(
              listOf(
                  WithdrawalsRow(
                      id = withdrawal.id,
                      facilityId = facilityId,
                      purposeId = WithdrawalPurpose.Other,
                      withdrawnDate = withdrawnDate,
                      createdBy = user.userId,
                      createdTime = withdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = withdrawalTime,
                  )
              ),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row",
          )
        },
        {
          assertEquals(
              listOf(
                  BatchWithdrawalsRow(
                      batchId = species1Batch1Id,
                      withdrawalId = withdrawal.id,
                      germinatingQuantityWithdrawn = 1,
                      activeGrowthQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3,
                      hardeningOffQuantityWithdrawn = 4,
                  )
              ),
              batchWithdrawalsDao.findAll(),
              "Should have inserted batch withdrawals row",
          )
        },
    )
  }

  @Test
  fun `quantity history effective times do not run backwards even for backdated withdrawals`() {
    val previousEffectiveTime =
        ZonedDateTime.of(2023, 1, 2, 10, 0, 0, 0, ZoneOffset.UTC).toInstant()
    val previousCreatedTime = ZonedDateTime.of(2023, 1, 2, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()
    insertBatchQuantityHistory(
        activeGrowthQuantity = 20,
        batchId = species1Batch1Id,
        createdTime = previousCreatedTime,
        effectiveTime = previousEffectiveTime,
        germinatingQuantity = 10,
        hardeningOffQuantity = 40,
        readyQuantity = 30,
    )

    val withdrawalTime = ZonedDateTime.of(2023, 1, 10, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()
    clock.instant = withdrawalTime
    val withdrawal =
        store.withdraw(
            NewWithdrawalModel(
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            activeGrowthQuantityWithdrawn = 2,
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 1,
                            hardeningOffQuantityWithdrawn = 4,
                            readyQuantityWithdrawn = 3,
                        )
                    ),
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.Other,
                withdrawnDate = LocalDate.of(2023, 1, 1),
            )
        )

    assertTableEquals(
        listOf(
            BatchQuantityHistoryRecord(
                activeGrowthQuantity = 20,
                batchId = species1Batch1Id,
                createdBy = user.userId,
                createdTime = previousCreatedTime,
                effectiveTime = previousEffectiveTime,
                germinatingQuantity = 10,
                hardeningOffQuantity = 40,
                historyTypeId = BatchQuantityHistoryType.Observed,
                readyQuantity = 30,
                version = 1,
            ),
            BatchQuantityHistoryRecord(
                activeGrowthQuantity = 18,
                batchId = species1Batch1Id,
                createdBy = user.userId,
                createdTime = withdrawalTime,
                effectiveTime = previousEffectiveTime,
                germinatingQuantity = 9,
                hardeningOffQuantity = 36,
                historyTypeId = BatchQuantityHistoryType.Computed,
                readyQuantity = 27,
                version = 2,
                withdrawalId = withdrawal.id,
            ),
        ),
        "History should not move backward in effective time",
    )
  }

  // In the current implementation, the summary is not actually "updated" (it is a query across the
  // underlying data that aggregates the results, so there's nothing to update) but that's an
  // implementation detail, not part of the API contract.
  @Test
  fun `updates species summary to reflect withdrawn quantities`() {
    batchQuantityHistoryDao.insert(
        BatchQuantityHistoryRow(
            batchId = species1Batch1Id,
            historyTypeId = BatchQuantityHistoryType.Observed,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            germinatingQuantity = 10,
            activeGrowthQuantity = 20,
            readyQuantity = 30,
            hardeningOffQuantity = 40,
            version = 1,
        )
    )

    val initialSummary = store.getSpeciesSummary(speciesId)

    store.withdraw(
        NewWithdrawalModel(
            facilityId = facilityId,
            id = null,
            purpose = WithdrawalPurpose.Dead,
            withdrawnDate = LocalDate.EPOCH,
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        batchId = species1Batch1Id,
                        germinatingQuantityWithdrawn = 1,
                        activeGrowthQuantityWithdrawn = 2,
                        readyQuantityWithdrawn = 3,
                        hardeningOffQuantityWithdrawn = 4,
                    )
                ),
        )
    )

    assertEquals(
        initialSummary.copy(
            germinatingQuantity = initialSummary.germinatingQuantity - 1,
            activeGrowthQuantity = initialSummary.activeGrowthQuantity - 2,
            readyQuantity = initialSummary.readyQuantity - 3,
            hardeningOffQuantity = initialSummary.hardeningOffQuantity - 4,
            lossRate = (9 * 100 / initialSummary.totalQuantity).toInt(),
            totalDead = 9,
            totalQuantity = initialSummary.totalQuantity - 9,
            totalWithdrawn = 9,
        ),
        store.getSpeciesSummary(speciesId),
    )
  }

  @Test
  fun `throws exception if trying to withdraw from the same batch twice in one withdrawal`() {
    assertThrows<IllegalArgumentException> {
      store.withdraw(
          NewWithdrawalModel(
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Dead,
              withdrawnDate = LocalDate.EPOCH,
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 0,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 0,
                      ),
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 0,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 0,
                      ),
                  ),
          )
      )
    }
  }

  @Test
  fun `throws exception if a batch has insufficient seedlings remaining`() {
    assertThrows<BatchInventoryInsufficientException> {
      store.withdraw(
          NewWithdrawalModel(
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Dead,
              withdrawnDate = LocalDate.EPOCH,
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1000,
                          activeGrowthQuantityWithdrawn = 2000,
                          readyQuantityWithdrawn = 3000,
                          hardeningOffQuantityWithdrawn = 4000,
                      )
                  ),
          )
      )
    }
  }

  @Test
  fun `throws exception if user has no permission to update a batch`() {
    every { user.canUpdateBatch(species1Batch2Id) } returns false

    assertThrows<AccessDeniedException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 1,
                      ),
                      BatchWithdrawalModel(
                          batchId = species1Batch2Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 1,
                      ),
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Dead,
              withdrawnDate = LocalDate.EPOCH,
          )
      )
    }
  }

  @Test
  fun `throws exception if any batches are not from requested facility ID`() {
    insertFacility(type = FacilityType.Nursery)

    val otherFacilityBatchId = insertBatch(speciesId = speciesId, germinatingQuantity = 1)

    assertThrows<IllegalArgumentException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 1,
                      ),
                      BatchWithdrawalModel(
                          batchId = otherFacilityBatchId,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      ),
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Dead,
              withdrawnDate = LocalDate.EPOCH,
          )
      )
    }
  }

  @Test
  fun `nursery transfer retains year and last part of original batch number`() {
    val expectedQuantity = expectedQuantitiesFunc()

    val species1Batch1 = batchesDao.fetchOneById(species1Batch1Id)!!
    val species1Batch2 = batchesDao.fetchOneById(species1Batch2Id)!!
    val species2Batch1 = batchesDao.fetchOneById(species2Batch1Id)!!

    val destinationFacilityId = insertFacility(type = FacilityType.Nursery, facilityNumber = 2)

    val newReadyByDate = LocalDate.of(2000, 1, 2)
    val effectiveTime = LocalDate.of(2022, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
    val withdrawalTime = ZonedDateTime.of(2025, 8, 29, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
    clock.instant = withdrawalTime

    fun expectedStageDetails(batchId: BatchId, stage: BatchStage, quantity: Int) =
        BatchStageDetailsRecord(
            batchId = batchId,
            countedQuantity = quantity,
            countedSeedlingDays = 0.0,
            finishedQuantity = 0.0,
            finishedSeedlingDays = 0.0,
            latestObservedQuantity = quantity,
            openSeedlingDays = 0.0,
            quantity = quantity,
            stageId = stage,
            timeInStageAsOf = effectiveTime,
            timeInStageCleared = false,
        )

    val withdrawal =
        store.withdraw(
            NewWithdrawalModel(
                destinationFacilityId = destinationFacilityId,
                facilityId = facilityId,
                id = null,
                notes = "Notes",
                purpose = WithdrawalPurpose.NurseryTransfer,
                withdrawnDate = LocalDate.of(2022, 10, 1),
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 1,
                            activeGrowthQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4,
                        ),
                        BatchWithdrawalModel(
                            batchId = species1Batch2Id,
                            germinatingQuantityWithdrawn = 4,
                            activeGrowthQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6,
                            hardeningOffQuantityWithdrawn = 7,
                        ),
                        BatchWithdrawalModel(
                            batchId = species2Batch1Id,
                            germinatingQuantityWithdrawn = 10,
                            activeGrowthQuantityWithdrawn = 11,
                            readyQuantityWithdrawn = 12,
                            hardeningOffQuantityWithdrawn = 13,
                        ),
                    ),
            ),
            newReadyByDate,
        )

    // The order the new batches get created is undefined, so either new batch ID/number could
    // be for either species. Need to load them to figure out which is which.
    val newBatches = batchesDao.fetchByFacilityId(destinationFacilityId)
    val newSpecies1Batch1 = newBatches.single {
      it.speciesId == speciesId && it.batchNumber!!.endsWith("11")
    }
    val newSpecies1Batch2 = newBatches.single {
      it.speciesId == speciesId && it.batchNumber!!.endsWith("12")
    }
    val newSpecies2Batch = newBatches.single { it.speciesId == speciesId2 }

    assertAll(
        {
          assertTableEquals(
              listOf(
                  expectedQuantity(species1Batch1, BatchStage.Germinating, 10 - 1),
                  expectedQuantity(species1Batch1, BatchStage.ActiveGrowth, 20 - 2),
                  expectedQuantity(species1Batch1, BatchStage.HardeningOff, 40 - 4),
                  expectedQuantity(species1Batch1, BatchStage.Ready, 30 - 3),
                  expectedQuantity(species1Batch2, BatchStage.Germinating, 40 - 4),
                  expectedQuantity(species1Batch2, BatchStage.ActiveGrowth, 50 - 5),
                  expectedQuantity(species1Batch2, BatchStage.HardeningOff, 70 - 7),
                  expectedQuantity(species1Batch2, BatchStage.Ready, 60 - 6),
                  expectedQuantity(species2Batch1, BatchStage.Germinating, 70 - 10),
                  expectedQuantity(species2Batch1, BatchStage.ActiveGrowth, 80 - 11),
                  expectedQuantity(species2Batch1, BatchStage.HardeningOff, 100 - 13),
                  expectedQuantity(species2Batch1, BatchStage.Ready, 90 - 12),
                  expectedStageDetails(newSpecies1Batch1.id!!, BatchStage.Germinating, 1),
                  expectedStageDetails(newSpecies1Batch1.id!!, BatchStage.ActiveGrowth, 2),
                  expectedStageDetails(newSpecies1Batch1.id!!, BatchStage.HardeningOff, 4),
                  expectedStageDetails(newSpecies1Batch1.id!!, BatchStage.Ready, 3),
                  expectedStageDetails(newSpecies1Batch2.id!!, BatchStage.Germinating, 4),
                  expectedStageDetails(newSpecies1Batch2.id!!, BatchStage.ActiveGrowth, 5),
                  expectedStageDetails(newSpecies1Batch2.id!!, BatchStage.HardeningOff, 7),
                  expectedStageDetails(newSpecies1Batch2.id!!, BatchStage.Ready, 6),
                  expectedStageDetails(newSpecies2Batch.id!!, BatchStage.Germinating, 10),
                  expectedStageDetails(newSpecies2Batch.id!!, BatchStage.ActiveGrowth, 11),
                  expectedStageDetails(newSpecies2Batch.id!!, BatchStage.HardeningOff, 13),
                  expectedStageDetails(newSpecies2Batch.id!!, BatchStage.Ready, 12),
              ),
          )
        },
        {
          assertEquals(
              listOf(
                  species1Batch1.copy(
                      totalLossCandidates = 20 + 30 + 40 - 2 - 3 - 4,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species1Batch2.copy(
                      totalLossCandidates = 50 + 60 + 70 - 5 - 6 - 7,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species2Batch1.copy(
                      totalLossCandidates = 80 + 90 + 100 - 11 - 12 - 13,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
              ),
              batchesDao.fetchByFacilityId(facilityId).sortedBy { it.id },
              "Should have deducted withdrawn quantities from batches",
          )
        },
        {
          val newBatch =
              BatchesRow(
                  addedDate = LocalDate.of(2022, 10, 1),
                  createdBy = user.userId,
                  createdTime = withdrawalTime,
                  facilityId = destinationFacilityId,
                  latestObservedTime =
                      ZonedDateTime.of(2022, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                  lossRate = 0,
                  modifiedBy = user.userId,
                  modifiedTime = withdrawalTime,
                  organizationId = organizationId,
                  readyByDate = newReadyByDate,
                  version = 1,
              )

          assertEquals(
              listOf(
                  newBatch.copy(
                      batchNumber = "21-2-2-011",
                      id = newSpecies1Batch1.id!!,
                      initialBatchId = species1Batch1Id,
                      speciesId = speciesId,
                  ),
                  newBatch.copy(
                      batchNumber = "21-2-2-012",
                      id = newSpecies1Batch2.id!!,
                      initialBatchId = species1Batch2Id,
                      speciesId = speciesId,
                  ),
                  newBatch.copy(
                      batchNumber = "21-2-2-021",
                      id = newSpecies2Batch.id!!,
                      initialBatchId = species2Batch1Id,
                      speciesId = speciesId2,
                  ),
              ),
              batchesDao.fetchByFacilityId(destinationFacilityId).sortedBy { it.batchNumber },
              "Should have created new batches",
          )
        },
        {
          assertEquals(
              listOf(
                  BatchWithdrawalsRow(
                      batchId = species1Batch1Id,
                      destinationBatchId = newSpecies1Batch1.id,
                      germinatingQuantityWithdrawn = 1,
                      activeGrowthQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3,
                      hardeningOffQuantityWithdrawn = 4,
                      withdrawalId = withdrawal.id,
                  ),
                  BatchWithdrawalsRow(
                      batchId = species1Batch2Id,
                      destinationBatchId = newSpecies1Batch2.id,
                      germinatingQuantityWithdrawn = 4,
                      activeGrowthQuantityWithdrawn = 5,
                      readyQuantityWithdrawn = 6,
                      hardeningOffQuantityWithdrawn = 7,
                      withdrawalId = withdrawal.id,
                  ),
                  BatchWithdrawalsRow(
                      batchId = species2Batch1Id,
                      destinationBatchId = newSpecies2Batch.id,
                      germinatingQuantityWithdrawn = 10,
                      activeGrowthQuantityWithdrawn = 11,
                      readyQuantityWithdrawn = 12,
                      hardeningOffQuantityWithdrawn = 13,
                      withdrawalId = withdrawal.id,
                  ),
              ),
              batchWithdrawalsDao.findAll().sortedBy { it.germinatingQuantityWithdrawn },
              "Should have created batch withdrawals",
          )
        },
        {
          val destinationBatchHistoryRow =
              BatchQuantityHistoryRow(
                  historyTypeId = BatchQuantityHistoryType.Observed,
                  createdBy = user.userId,
                  createdTime = withdrawalTime,
                  withdrawalId = withdrawal.id,
                  effectiveTime =
                      ZonedDateTime.of(2022, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(),
              )
          val originBatchHistoryRow =
              BatchQuantityHistoryRow(
                  historyTypeId = BatchQuantityHistoryType.Computed,
                  createdBy = user.userId,
                  createdTime = withdrawalTime,
                  withdrawalId = withdrawal.id,
                  effectiveTime =
                      ZonedDateTime.of(2022, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(),
              )

          assertEquals(
              listOf(
                  destinationBatchHistoryRow.copy(
                      batchId = newSpecies1Batch1.id!!,
                      germinatingQuantity = 1,
                      activeGrowthQuantity = 2,
                      readyQuantity = 3,
                      hardeningOffQuantity = 4,
                      version = 1,
                  ),
                  destinationBatchHistoryRow.copy(
                      batchId = newSpecies1Batch2.id!!,
                      germinatingQuantity = 4,
                      activeGrowthQuantity = 5,
                      readyQuantity = 6,
                      hardeningOffQuantity = 7,
                      version = 1,
                  ),
                  originBatchHistoryRow.copy(
                      batchId = species1Batch1Id,
                      germinatingQuantity = 10 - 1,
                      activeGrowthQuantity = 20 - 2,
                      readyQuantity = 30 - 3,
                      hardeningOffQuantity = 40 - 4,
                      version = 2,
                  ),
                  destinationBatchHistoryRow.copy(
                      batchId = newSpecies2Batch.id!!,
                      germinatingQuantity = 10,
                      activeGrowthQuantity = 11,
                      readyQuantity = 12,
                      hardeningOffQuantity = 13,
                      version = 1,
                  ),
                  originBatchHistoryRow.copy(
                      batchId = species1Batch2Id,
                      germinatingQuantity = 40 - 4,
                      activeGrowthQuantity = 50 - 5,
                      readyQuantity = 60 - 6,
                      hardeningOffQuantity = 70 - 7,
                      version = 2,
                  ),
                  originBatchHistoryRow.copy(
                      batchId = species2Batch1Id,
                      germinatingQuantity = 70 - 10,
                      activeGrowthQuantity = 80 - 11,
                      readyQuantity = 90 - 12,
                      hardeningOffQuantity = 100 - 13,
                      version = 2,
                  ),
              ),
              batchQuantityHistoryDao
                  .findAll()
                  .map { it.copy(id = null) }
                  .sortedBy { it.germinatingQuantity!! },
              "Should have inserted quantity history rows",
          )
        },
        {
          assertEquals(
              listOf(
                  WithdrawalsRow(
                      id = withdrawal.id,
                      facilityId = facilityId,
                      notes = "Notes",
                      purposeId = WithdrawalPurpose.NurseryTransfer,
                      withdrawnDate = LocalDate.of(2022, 10, 1),
                      createdBy = user.userId,
                      createdTime = withdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = withdrawalTime,
                      destinationFacilityId = destinationFacilityId,
                  )
              ),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row",
          )
        },
    )
  }

  @Test
  fun `nursery transfer retains original accession IDs`() {
    insertFacility()
    val accessionId1 = insertAccession()
    val accessionId2 = insertAccession()

    batchesDao.update(batchesDao.fetchOneById(species1Batch1Id)!!.copy(accessionId = accessionId1))
    batchesDao.update(batchesDao.fetchOneById(species1Batch2Id)!!.copy(accessionId = accessionId2))

    val destinationFacilityId = insertFacility(type = FacilityType.Nursery, facilityNumber = 2)

    val withdrawnDate = LocalDate.of(2022, 10, 1)
    clock.instant = withdrawnDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    store.withdraw(
        NewWithdrawalModel(
            destinationFacilityId = destinationFacilityId,
            facilityId = facilityId,
            id = null,
            notes = "Notes",
            purpose = WithdrawalPurpose.NurseryTransfer,
            withdrawnDate = withdrawnDate,
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        batchId = species1Batch1Id,
                        germinatingQuantityWithdrawn = 0,
                        activeGrowthQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 1,
                        hardeningOffQuantityWithdrawn = 0,
                    ),
                    BatchWithdrawalModel(
                        batchId = species1Batch2Id,
                        germinatingQuantityWithdrawn = 0,
                        activeGrowthQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 2,
                        hardeningOffQuantityWithdrawn = 0,
                    ),
                ),
        )
    )

    val newBatches =
        dslContext
            .select(BATCHES.ACCESSION_ID, BATCH_STAGE_DETAILS.QUANTITY)
            .from(BATCHES)
            .join(BATCH_STAGE_DETAILS)
            .on(
                BATCH_STAGE_DETAILS.BATCH_ID.eq(BATCHES.ID)
                    .and(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.Ready))
            )
            .where(BATCHES.FACILITY_ID.eq(destinationFacilityId))
            .fetchMap(BATCHES.ACCESSION_ID, BATCH_STAGE_DETAILS.QUANTITY)

    assertEquals(
        mapOf(accessionId1 to 1, accessionId2 to 2),
        newBatches,
        "Accession IDs of new batches",
    )
  }

  @Test
  fun `nursery transfer retains original project associations`() {
    insertFacility()
    val projectId1 = insertProject()
    val projectId2 = insertProject()

    batchesDao.update(batchesDao.fetchOneById(species1Batch1Id)!!.copy(projectId = projectId1))
    batchesDao.update(batchesDao.fetchOneById(species1Batch2Id)!!.copy(projectId = projectId2))

    val destinationFacilityId = insertFacility(type = FacilityType.Nursery, facilityNumber = 2)

    val withdrawnDate = LocalDate.of(2022, 10, 1)
    clock.instant = withdrawnDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    store.withdraw(
        NewWithdrawalModel(
            destinationFacilityId = destinationFacilityId,
            facilityId = facilityId,
            id = null,
            notes = "Notes",
            purpose = WithdrawalPurpose.NurseryTransfer,
            withdrawnDate = withdrawnDate,
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        batchId = species1Batch1Id,
                        germinatingQuantityWithdrawn = 0,
                        activeGrowthQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 1,
                        hardeningOffQuantityWithdrawn = 0,
                    ),
                    BatchWithdrawalModel(
                        batchId = species1Batch2Id,
                        germinatingQuantityWithdrawn = 0,
                        activeGrowthQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 2,
                        hardeningOffQuantityWithdrawn = 0,
                    ),
                ),
        )
    )

    val newBatches =
        dslContext
            .select(BATCHES.PROJECT_ID, BATCH_STAGE_DETAILS.QUANTITY)
            .from(BATCHES)
            .join(BATCH_STAGE_DETAILS)
            .on(
                BATCH_STAGE_DETAILS.BATCH_ID.eq(BATCHES.ID)
                    .and(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.Ready))
            )
            .where(BATCHES.FACILITY_ID.eq(destinationFacilityId))
            .fetchMap(BATCHES.PROJECT_ID, BATCH_STAGE_DETAILS.QUANTITY)

    assertEquals(
        mapOf(projectId1 to 1, projectId2 to 2),
        newBatches,
        "Project IDs of new batches",
    )
  }

  @Test
  fun `nursery transfer adds to existing batch if batch number already exists`() {
    val expectedQuantity = expectedQuantitiesFunc()

    val species1Batch1 = batchesDao.fetchOneById(species1Batch1Id)!!

    val destinationTimeZone = ZoneId.of("Asia/Tokyo")
    val destinationFacilityId =
        insertFacility(
            type = FacilityType.Nursery,
            facilityNumber = 2,
            timeZone = destinationTimeZone,
        )

    val newReadyByDate = LocalDate.of(2000, 1, 2)
    val secondEffectiveTime =
        LocalDate.of(2022, 10, 2).atStartOfDay(destinationTimeZone).toInstant()
    val firstWithdrawalTime = ZonedDateTime.of(2025, 8, 29, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
    clock.instant = firstWithdrawalTime

    val firstWithdrawal =
        store.withdraw(
            NewWithdrawalModel(
                destinationFacilityId = destinationFacilityId,
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.NurseryTransfer,
                withdrawnDate = LocalDate.of(2022, 10, 1),
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 1,
                            activeGrowthQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4,
                        )
                    ),
            ),
            newReadyByDate,
        )

    val secondWithdrawalTime = ZonedDateTime.of(2025, 8, 30, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
    clock.instant = secondWithdrawalTime

    val secondWithdrawal =
        store.withdraw(
            NewWithdrawalModel(
                destinationFacilityId = destinationFacilityId,
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.NurseryTransfer,
                withdrawnDate = LocalDate.of(2022, 10, 2),
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 4,
                            activeGrowthQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6,
                            hardeningOffQuantityWithdrawn = 7,
                        )
                    ),
            ),
            newReadyByDate,
        )

    val newBatch = batchesDao.fetchByFacilityId(destinationFacilityId).first()

    fun expectedStageDetails(
        stage: BatchStage,
        quantity: Int,
        latestObservedQuantity: Int,
        days: Double,
    ) =
        BatchStageDetailsRecord(
            batchId = newBatch.id!!,
            countedQuantity = quantity,
            countedSeedlingDays = days,
            finishedQuantity = 0.0,
            finishedSeedlingDays = 0.0,
            latestObservedQuantity = latestObservedQuantity,
            openSeedlingDays = days,
            quantity = quantity,
            stageId = stage,
            timeInStageAsOf = secondEffectiveTime,
            timeInStageCleared = false,
        )

    assertAll(
        {
          assertTableEquals(
              listOf(
                  expectedQuantity(species1Batch1, BatchStage.Germinating, 10 - 1 - 4),
                  expectedQuantity(species1Batch1, BatchStage.ActiveGrowth, 20 - 2 - 5),
                  expectedQuantity(species1Batch1, BatchStage.HardeningOff, 40 - 4 - 7),
                  expectedQuantity(species1Batch1, BatchStage.Ready, 30 - 3 - 6),
                  expectedStageDetails(BatchStage.Germinating, 5, 1, 1.0),
                  expectedStageDetails(BatchStage.ActiveGrowth, 7, 2, 2.0),
                  expectedStageDetails(BatchStage.HardeningOff, 11, 4, 4.0),
                  expectedStageDetails(BatchStage.Ready, 9, 3, 3.0),
              ),
              where = BATCH_STAGE_DETAILS.BATCH_ID.`in`(species1Batch1Id, newBatch.id!!),
          )
        },
        {
          assertEquals(
              species1Batch1.copy(
                  totalLossCandidates = 20 + 30 + 40 - 2 - 5 - 3 - 6 - 4 - 7,
                  modifiedTime = secondWithdrawalTime,
                  version = 3,
              ),
              batchesDao.fetchOneById(species1Batch1Id),
              "Should have deducted withdrawn quantities from batch",
          )
        },
        {
          assertEquals(
              listOf(
                  BatchesRow(
                      addedDate = LocalDate.of(2022, 10, 1),
                      batchNumber = "21-2-2-011",
                      createdBy = user.userId,
                      createdTime = firstWithdrawalTime,
                      facilityId = destinationFacilityId,
                      id = newBatch.id!!,
                      initialBatchId = species1Batch1Id,
                      latestObservedTime =
                          ZonedDateTime.of(2022, 10, 1, 0, 0, 0, 0, destinationTimeZone)
                              .toInstant(),
                      lossRate = 0,
                      modifiedBy = user.userId,
                      modifiedTime = secondWithdrawalTime,
                      organizationId = organizationId,
                      readyByDate = newReadyByDate,
                      speciesId = speciesId,
                      totalLossCandidates = (2 + 5) + (3 + 6) + (4 + 7),
                      totalLost = 0,
                      version = 2,
                  ),
              ),
              batchesDao.fetchByFacilityId(destinationFacilityId),
              "Should have created one new batch",
          )
        },
        {
          assertEquals(
              listOf(
                  BatchWithdrawalsRow(
                      batchId = species1Batch1Id,
                      destinationBatchId = newBatch.id,
                      germinatingQuantityWithdrawn = 1,
                      activeGrowthQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3,
                      hardeningOffQuantityWithdrawn = 4,
                      withdrawalId = firstWithdrawal.id,
                  ),
                  BatchWithdrawalsRow(
                      batchId = species1Batch1Id,
                      destinationBatchId = newBatch.id,
                      germinatingQuantityWithdrawn = 4,
                      activeGrowthQuantityWithdrawn = 5,
                      readyQuantityWithdrawn = 6,
                      hardeningOffQuantityWithdrawn = 7,
                      withdrawalId = secondWithdrawal.id,
                  ),
              ),
              batchWithdrawalsDao.findAll().sortedBy { it.germinatingQuantityWithdrawn },
              "Should have created batch withdrawals",
          )
        },
        {
          assertSetEquals(
              setOf(
                  BatchQuantityHistoryRow(
                      batchId = newBatch.id!!,
                      createdBy = user.userId,
                      createdTime = firstWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Observed,
                      germinatingQuantity = 1,
                      activeGrowthQuantity = 2,
                      readyQuantity = 3,
                      hardeningOffQuantity = 4,
                      withdrawalId = firstWithdrawal.id,
                      version = 1,
                      effectiveTime =
                          ZonedDateTime.of(
                                  2022,
                                  10,
                                  1,
                                  0,
                                  0,
                                  0,
                                  0,
                                  destinationTimeZone,
                              )
                              .toInstant(),
                  ),
                  BatchQuantityHistoryRow(
                      batchId = newBatch.id!!,
                      createdBy = user.userId,
                      createdTime = secondWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Computed,
                      germinatingQuantity = 1 + 4,
                      activeGrowthQuantity = 2 + 5,
                      readyQuantity = 3 + 6,
                      hardeningOffQuantity = 4 + 7,
                      withdrawalId = secondWithdrawal.id,
                      version = 2,
                      effectiveTime =
                          ZonedDateTime.of(
                                  2022,
                                  10,
                                  2,
                                  0,
                                  0,
                                  0,
                                  0,
                                  destinationTimeZone,
                              )
                              .toInstant(),
                  ),
                  BatchQuantityHistoryRow(
                      batchId = species1Batch1Id,
                      createdBy = user.userId,
                      createdTime = firstWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Computed,
                      germinatingQuantity = 10 - 1,
                      activeGrowthQuantity = 20 - 2,
                      readyQuantity = 30 - 3,
                      hardeningOffQuantity = 40 - 4,
                      withdrawalId = firstWithdrawal.id,
                      version = 2,
                      effectiveTime =
                          ZonedDateTime.of(2022, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                  ),
                  BatchQuantityHistoryRow(
                      batchId = species1Batch1Id,
                      createdBy = user.userId,
                      createdTime = secondWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Computed,
                      germinatingQuantity = 10 - 1 - 4,
                      activeGrowthQuantity = 20 - 2 - 5,
                      readyQuantity = 30 - 3 - 6,
                      hardeningOffQuantity = 40 - 4 - 7,
                      withdrawalId = secondWithdrawal.id,
                      version = 3,
                      effectiveTime =
                          ZonedDateTime.of(2022, 10, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                  ),
              ),
              batchQuantityHistoryDao.findAll().map { it.copy(id = null) }.toSet(),
              "Should have inserted quantity history rows",
          )
        },
        {
          assertSetEquals(
              setOf(
                  WithdrawalsRow(
                      id = firstWithdrawal.id,
                      facilityId = facilityId,
                      purposeId = WithdrawalPurpose.NurseryTransfer,
                      withdrawnDate = LocalDate.of(2022, 10, 1),
                      createdBy = user.userId,
                      createdTime = firstWithdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = firstWithdrawalTime,
                      destinationFacilityId = destinationFacilityId,
                  ),
                  WithdrawalsRow(
                      id = secondWithdrawal.id,
                      facilityId = facilityId,
                      purposeId = WithdrawalPurpose.NurseryTransfer,
                      withdrawnDate = LocalDate.of(2022, 10, 2),
                      createdBy = user.userId,
                      createdTime = secondWithdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = secondWithdrawalTime,
                      destinationFacilityId = destinationFacilityId,
                  ),
              ),
              nurseryWithdrawalsDao.findAll().toSet(),
              "Should have inserted withdrawals rows",
          )
        },
    )
  }

  @Test
  fun `throws exception if destination facility is in a different organization`() {
    insertOrganization()
    val otherOrgFacilityId = insertFacility(type = FacilityType.Nursery)

    assertThrows<CrossOrganizationNurseryTransferNotAllowedException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              destinationFacilityId = otherOrgFacilityId,
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.EPOCH,
          )
      )
    }
  }

  @Test
  fun `throws exception if no permission to create batches at destination facility`() {
    val destinationFacilityId = insertFacility(type = FacilityType.Nursery)

    every { user.canCreateBatch(destinationFacilityId) } returns false
    every { user.canReadFacility(destinationFacilityId) } returns true

    assertThrows<AccessDeniedException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              destinationFacilityId = destinationFacilityId,
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.EPOCH,
          )
      )
    }
  }

  @Test
  fun `stores plantingSeasonId in withdrawal`() {
    insertPlantingSite()
    val plantingSeasonId = insertPlantingSeason()

    store.withdraw(
        NewWithdrawalModel(
            facilityId = facilityId,
            id = null,
            purpose = WithdrawalPurpose.OutPlant,
            withdrawnDate = LocalDate.EPOCH,
            plantingSeasonId = plantingSeasonId,
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        batchId = species1Batch1Id,
                        germinatingQuantityWithdrawn = 1,
                        activeGrowthQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 0,
                        hardeningOffQuantityWithdrawn = 0,
                    )
                ),
        )
    )

    assertTableEquals(
        WithdrawalsRecord(
            facilityId = facilityId,
            purposeId = WithdrawalPurpose.OutPlant,
            withdrawnDate = LocalDate.EPOCH,
            plantingSeasonId = plantingSeasonId,
            createdBy = user.userId,
            createdTime = clock.instant(),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
        )
    )
  }

  @Test
  fun `throws exception if planting season id is specified but not found`() {
    assertThrows<DataIntegrityViolationException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.EPOCH,
              plantingSeasonId = PlantingSeasonId(99999L),
          )
      )
    }
  }

  @Test
  fun `throws exception if planting season id and purpose is not OutPlant`() {
    insertPlantingSite()
    val plantingSeasonId = insertPlantingSeason()

    assertThrows<IllegalArgumentException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              facilityId = facilityId,
              id = null,
              plantingSeasonId = plantingSeasonId,
              purpose = WithdrawalPurpose.Other,
              withdrawnDate = LocalDate.EPOCH,
          )
      )
    }
  }

  @Test
  fun `stores scheduledPlantingDateRequestId in withdrawal`() {
    insertPlantingSite()
    val plantingSeasonId = insertPlantingSeason()
    val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
    insertPlantingDateRequest()

    store.withdraw(
        NewWithdrawalModel(
            facilityId = facilityId,
            id = null,
            purpose = WithdrawalPurpose.OutPlant,
            withdrawnDate = LocalDate.EPOCH,
            plantingSeasonId = plantingSeasonId,
            scheduledPlantingDateRequestId = scheduledPlantingDateId,
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        batchId = species1Batch1Id,
                        germinatingQuantityWithdrawn = 1,
                        activeGrowthQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 0,
                        hardeningOffQuantityWithdrawn = 0,
                    )
                ),
        )
    )

    assertTableEquals(
        WithdrawalsRecord(
            facilityId = facilityId,
            purposeId = WithdrawalPurpose.OutPlant,
            withdrawnDate = LocalDate.EPOCH,
            plantingSeasonId = plantingSeasonId,
            createdBy = user.userId,
            createdTime = clock.instant(),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            scheduledPlantingDateRequestId = scheduledPlantingDateId,
        )
    )

    eventPublisher.assertEventPublished(
        WithdrawalAssociatedWithPlantingDateRequestEvent(
            isUndo = false,
            scheduledPlantingDateRequestId = scheduledPlantingDateId,
        )
    )
  }

  @Test
  fun `rejects withdrawals to fulfilled planting date requests`() {
    insertPlantingSite()
    val plantingSeasonId = insertPlantingSeason()
    val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
    insertPlantingDateRequest(status = PlantingDateRequestStatus.Fulfilled)

    assertThrows<PlantingSeasonDateRequestFulfilledException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.EPOCH,
              plantingSeasonId = plantingSeasonId,
              scheduledPlantingDateRequestId = scheduledPlantingDateId,
          )
      )
    }
  }

  @Test
  fun `throws exception if scheduled planting date id is specified but not found`() {
    insertPlantingSite()
    val plantingSeasonId = insertPlantingSeason()

    assertThrows<PlantingSeasonScheduledDateNotFoundException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.EPOCH,
              plantingSeasonId = plantingSeasonId,
              scheduledPlantingDateRequestId = ScheduledPlantingDateId(99999L),
          )
      )
    }
  }

  @Test
  fun `throws exception if scheduled planting date id is not associated with request`() {
    insertPlantingSite()
    val plantingSeasonId = insertPlantingSeason()
    val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()

    assertThrows<PlantingSeasonDateRequestNotFoundException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.EPOCH,
              plantingSeasonId = plantingSeasonId,
              scheduledPlantingDateRequestId = scheduledPlantingDateId,
          )
      )
    }
  }

  @Test
  fun `throws exception if planting date request id and no planting season id`() {
    insertPlantingSite()
    insertStratum()
    insertSubstratum()
    insertPlantingSeason()
    val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
    insertPlantingDateRequest()
    insertPlantingDateRequestSpecies()

    assertThrows<IllegalArgumentException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          activeGrowthQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0,
                      )
                  ),
              facilityId = facilityId,
              id = null,
              scheduledPlantingDateRequestId = scheduledPlantingDateId,
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.EPOCH,
          )
      )
    }
  }

  private fun expectedQuantitiesFunc(): (BatchesRow, BatchStage, Int) -> BatchStageDetailsRecord {
    val initialQuantities =
        dslContext
            .selectFrom(BATCH_STAGE_DETAILS)
            .fetchInto(BatchStageDetailsRow::class.java)
            .associateBy { it.batchId to it.stageId }

    return { batch, stage, quantity ->
      BatchStageDetailsRecord(
          initialQuantities.getValue(batch.id to stage).copy(quantity = quantity)
      )
    }
  }
}
