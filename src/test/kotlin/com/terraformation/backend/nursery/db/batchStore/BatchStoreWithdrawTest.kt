package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.nursery.db.BatchInventoryInsufficientException
import com.terraformation.backend.nursery.db.CrossOrganizationNurseryTransferNotAllowedException
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
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
            notReadyQuantity = 20,
            readyQuantity = 30,
            hardeningOffQuantity = 40,
            totalLost = 0,
            totalLossCandidates = 20 + 30 + 40)
    species1Batch2Id =
        insertBatch(
            speciesId = speciesId,
            batchNumber = "21-2-1-012",
            germinatingQuantity = 40,
            notReadyQuantity = 50,
            readyQuantity = 60,
            hardeningOffQuantity = 70,
            totalLost = 0,
            totalLossCandidates = 50 + 60 + 70)
    species2Batch1Id =
        insertBatch(
            speciesId = speciesId2,
            batchNumber = "21-2-1-021",
            germinatingQuantity = 70,
            notReadyQuantity = 80,
            readyQuantity = 90,
            hardeningOffQuantity = 100,
            totalLost = 0,
            totalLossCandidates = 80 + 90 + 100)
  }

  @Test
  fun `can withdraw from multiple batches`() {
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
                withdrawnDate = LocalDate.of(2022, 10, 1),
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            batchId = species1Batch1Id,
                            germinatingQuantityWithdrawn = 1,
                            notReadyQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4),
                        BatchWithdrawalModel(
                            batchId = species1Batch2Id,
                            germinatingQuantityWithdrawn = 4,
                            notReadyQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6,
                            hardeningOffQuantityWithdrawn = 7),
                        BatchWithdrawalModel(
                            batchId = species2Batch1Id,
                            germinatingQuantityWithdrawn = 7,
                            notReadyQuantityWithdrawn = 8,
                            readyQuantityWithdrawn = 9,
                            hardeningOffQuantityWithdrawn = 10))))

    assertAll(
        {
          assertEquals(
              listOf(
                  species1Batch1.copy(
                      germinatingQuantity = 10 - 1,
                      activeGrowthQuantity = 20 - 2,
                      readyQuantity = 30 - 3,
                      hardeningOffQuantity = 40 - 4,
                      totalLossCandidates = 20 + 30 + 40 - 2 - 3 - 4,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species1Batch2.copy(
                      germinatingQuantity = 40 - 4,
                      activeGrowthQuantity = 50 - 5,
                      readyQuantity = 60 - 6,
                      hardeningOffQuantity = 70 - 7,
                      totalLossCandidates = 50 + 60 + 70 - 5 - 6 - 7,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species2Batch1.copy(
                      germinatingQuantity = 70 - 7,
                      activeGrowthQuantity = 80 - 8,
                      readyQuantity = 90 - 9,
                      hardeningOffQuantity = 100 - 10,
                      totalLossCandidates = 80 + 90 + 100 - 8 - 9 - 10,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
              ),
              batchesDao.findAll().sortedBy { it.germinatingQuantity!! },
              "Should have deducted withdrawn quantities from batches")
        },
        {
          val newHistoryRow =
              BatchQuantityHistoryRow(
                  historyTypeId = BatchQuantityHistoryType.Computed,
                  createdBy = user.userId,
                  createdTime = withdrawalTime,
                  withdrawalId = withdrawal.id,
                  version = 2)

          assertEquals(
              listOf(
                  newHistoryRow.copy(
                      batchId = species1Batch1Id,
                      germinatingQuantity = 9,
                      activeGrowthQuantity = 18,
                      readyQuantity = 27,
                      hardeningOffQuantity = 36),
                  newHistoryRow.copy(
                      batchId = species1Batch2Id,
                      germinatingQuantity = 36,
                      activeGrowthQuantity = 45,
                      readyQuantity = 54,
                      hardeningOffQuantity = 63),
                  newHistoryRow.copy(
                      batchId = species2Batch1Id,
                      germinatingQuantity = 63,
                      activeGrowthQuantity = 72,
                      readyQuantity = 81,
                      hardeningOffQuantity = 90)),
              batchQuantityHistoryDao
                  .findAll()
                  .map { it.copy(id = null) }
                  .sortedBy { it.germinatingQuantity!! },
              "Should have inserted quantity history rows")
        },
        {
          assertEquals(
              listOf(
                  WithdrawalsRow(
                      id = withdrawal.id,
                      facilityId = facilityId,
                      notes = "Notes",
                      purposeId = WithdrawalPurpose.Other,
                      withdrawnDate = LocalDate.of(2022, 10, 1),
                      createdBy = user.userId,
                      createdTime = withdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = withdrawalTime)),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row")
        })
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
                            notReadyQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4))))

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(version = 2, modifiedTime = withdrawalTime),
              batchesDao.fetchOneById(species1Batch1Id),
              "Should not have deducted withdrawn quantities from batch")
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
                      modifiedTime = withdrawalTime)),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row")
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
                      hardeningOffQuantityWithdrawn = 4)),
              batchWithdrawalsDao.findAll(),
              "Should have inserted batch withdrawals row")
        },
    )
  }

  @Test
  fun `uses nursery time zone to determine date of last observation`() {
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
                            notReadyQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4))))

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(
                  version = 2,
                  modifiedTime = withdrawalTime,
                  germinatingQuantity = 10 - 1,
                  activeGrowthQuantity = 20 - 2,
                  readyQuantity = 30 - 3,
                  hardeningOffQuantity = 40 - 4,
                  totalLossCandidates = 20 + 30 + 40 - 2 - 3 - 4,
              ),
              batchesDao.fetchOneById(species1Batch1Id),
              "Should have deducted withdrawn quantities from batch")
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
                  ),
              ),
              batchQuantityHistoryDao.findAll().map { it.copy(id = null) },
              "Should have inserted quantity history row")
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
                      modifiedTime = withdrawalTime)),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row")
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
                      hardeningOffQuantityWithdrawn = 4)),
              batchWithdrawalsDao.findAll(),
              "Should have inserted batch withdrawals row")
        },
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
            version = 1))

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
                        notReadyQuantityWithdrawn = 2,
                        readyQuantityWithdrawn = 3,
                        hardeningOffQuantityWithdrawn = 4))))

    assertEquals(
        initialSummary.copy(
            germinatingQuantity = initialSummary.germinatingQuantity - 1,
            notReadyQuantity = initialSummary.notReadyQuantity - 2,
            readyQuantity = initialSummary.readyQuantity - 3,
            hardeningOffQuantity = initialSummary.hardeningOffQuantity - 4,
            lossRate = (9 * 100 / initialSummary.totalQuantity).toInt(),
            totalDead = 9,
            totalQuantity = initialSummary.totalQuantity - 9,
            totalWithdrawn = 9,
        ),
        store.getSpeciesSummary(speciesId))
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
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 0),
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 0,
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 0))))
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
                          notReadyQuantityWithdrawn = 2000,
                          readyQuantityWithdrawn = 3000,
                          hardeningOffQuantityWithdrawn = 4000))))
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
                          notReadyQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 1),
                      BatchWithdrawalModel(
                          batchId = species1Batch2Id,
                          germinatingQuantityWithdrawn = 1,
                          notReadyQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 1),
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Dead,
              withdrawnDate = LocalDate.EPOCH))
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
                          notReadyQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1,
                          hardeningOffQuantityWithdrawn = 1),
                      BatchWithdrawalModel(
                          batchId = otherFacilityBatchId,
                          germinatingQuantityWithdrawn = 1,
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0),
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Dead,
              withdrawnDate = LocalDate.EPOCH))
    }
  }

  @Test
  fun `nursery transfer retains year and last part of original batch number`() {
    val species1Batch1 = batchesDao.fetchOneById(species1Batch1Id)!!
    val species1Batch2 = batchesDao.fetchOneById(species1Batch2Id)!!
    val species2Batch1 = batchesDao.fetchOneById(species2Batch1Id)!!

    val destinationFacilityId = insertFacility(type = FacilityType.Nursery, facilityNumber = 2)

    val newReadyByDate = LocalDate.of(2000, 1, 2)
    val withdrawalTime = ZonedDateTime.of(2023, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
    clock.instant = withdrawalTime

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
                            notReadyQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4),
                        BatchWithdrawalModel(
                            batchId = species1Batch2Id,
                            germinatingQuantityWithdrawn = 4,
                            notReadyQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6,
                            hardeningOffQuantityWithdrawn = 7),
                        BatchWithdrawalModel(
                            batchId = species2Batch1Id,
                            germinatingQuantityWithdrawn = 10,
                            notReadyQuantityWithdrawn = 11,
                            readyQuantityWithdrawn = 12,
                            hardeningOffQuantityWithdrawn = 13))),
            newReadyByDate)

    // The order the new batches get created is undefined, so either new batch ID/number could
    // be for either species. Need to load them to figure out which is which.
    val newBatches = batchesDao.fetchByFacilityId(destinationFacilityId)
    val newSpecies1Batch1 =
        newBatches.single { it.speciesId == speciesId && it.batchNumber!!.endsWith("11") }
    val newSpecies1Batch2 =
        newBatches.single { it.speciesId == speciesId && it.batchNumber!!.endsWith("12") }
    val newSpecies2Batch = newBatches.single { it.speciesId == speciesId2 }

    assertAll(
        {
          assertEquals(
              listOf(
                  species1Batch1.copy(
                      germinatingQuantity = 10 - 1,
                      activeGrowthQuantity = 20 - 2,
                      readyQuantity = 30 - 3,
                      hardeningOffQuantity = 40 - 4,
                      totalLossCandidates = 20 + 30 + 40 - 2 - 3 - 4,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species1Batch2.copy(
                      germinatingQuantity = 40 - 4,
                      activeGrowthQuantity = 50 - 5,
                      readyQuantity = 60 - 6,
                      hardeningOffQuantity = 70 - 7,
                      totalLossCandidates = 50 + 60 + 70 - 5 - 6 - 7,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species2Batch1.copy(
                      germinatingQuantity = 70 - 10,
                      activeGrowthQuantity = 80 - 11,
                      readyQuantity = 90 - 12,
                      hardeningOffQuantity = 100 - 13,
                      totalLossCandidates = 80 + 90 + 100 - 11 - 12 - 13,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
              ),
              batchesDao.fetchByFacilityId(facilityId).sortedBy { it.germinatingQuantity!! },
              "Should have deducted withdrawn quantities from batches")
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
                  version = 1)

          assertEquals(
              listOf(
                  newBatch.copy(
                      batchNumber = "21-2-2-011",
                      id = newSpecies1Batch1.id!!,
                      initialBatchId = species1Batch1Id,
                      germinatingQuantity = 1,
                      activeGrowthQuantity = 2,
                      readyQuantity = 3,
                      hardeningOffQuantity = 4,
                      latestObservedGerminatingQuantity = 1,
                      latestObservedActiveGrowthQuantity = 2,
                      latestObservedReadyQuantity = 3,
                      latestObservedHardeningOffQuantity = 4,
                      speciesId = speciesId,
                  ),
                  newBatch.copy(
                      batchNumber = "21-2-2-012",
                      id = newSpecies1Batch2.id!!,
                      initialBatchId = species1Batch2Id,
                      germinatingQuantity = 4,
                      activeGrowthQuantity = 5,
                      readyQuantity = 6,
                      hardeningOffQuantity = 7,
                      latestObservedGerminatingQuantity = 4,
                      latestObservedActiveGrowthQuantity = 5,
                      latestObservedReadyQuantity = 6,
                      latestObservedHardeningOffQuantity = 7,
                      speciesId = speciesId,
                  ),
                  newBatch.copy(
                      batchNumber = "21-2-2-021",
                      id = newSpecies2Batch.id!!,
                      initialBatchId = species2Batch1Id,
                      germinatingQuantity = 10,
                      activeGrowthQuantity = 11,
                      readyQuantity = 12,
                      hardeningOffQuantity = 13,
                      latestObservedGerminatingQuantity = 10,
                      latestObservedActiveGrowthQuantity = 11,
                      latestObservedReadyQuantity = 12,
                      latestObservedHardeningOffQuantity = 13,
                      speciesId = speciesId2,
                  ),
              ),
              batchesDao.fetchByFacilityId(destinationFacilityId).sortedBy {
                it.germinatingQuantity
              },
              "Should have created new batches")
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
                      withdrawalId = withdrawal.id),
                  BatchWithdrawalsRow(
                      batchId = species1Batch2Id,
                      destinationBatchId = newSpecies1Batch2.id,
                      germinatingQuantityWithdrawn = 4,
                      activeGrowthQuantityWithdrawn = 5,
                      readyQuantityWithdrawn = 6,
                      hardeningOffQuantityWithdrawn = 7,
                      withdrawalId = withdrawal.id),
                  BatchWithdrawalsRow(
                      batchId = species2Batch1Id,
                      destinationBatchId = newSpecies2Batch.id,
                      germinatingQuantityWithdrawn = 10,
                      activeGrowthQuantityWithdrawn = 11,
                      readyQuantityWithdrawn = 12,
                      hardeningOffQuantityWithdrawn = 13,
                      withdrawalId = withdrawal.id),
              ),
              batchWithdrawalsDao.findAll().sortedBy { it.germinatingQuantityWithdrawn },
              "Should have created batch withdrawals")
        },
        {
          val destinationBatchHistoryRow =
              BatchQuantityHistoryRow(
                  historyTypeId = BatchQuantityHistoryType.Observed,
                  createdBy = user.userId,
                  createdTime = withdrawalTime,
                  withdrawalId = withdrawal.id)
          val originBatchHistoryRow =
              BatchQuantityHistoryRow(
                  historyTypeId = BatchQuantityHistoryType.Computed,
                  createdBy = user.userId,
                  createdTime = withdrawalTime,
                  withdrawalId = withdrawal.id)

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
              "Should have inserted quantity history rows")
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
                      destinationFacilityId = destinationFacilityId)),
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals row")
        })
  }

  @Test
  fun `nursery transfer retains original accession IDs`() {
    insertFacility()
    val accessionId1 = insertAccession()
    val accessionId2 = insertAccession()

    batchesDao.update(batchesDao.fetchOneById(species1Batch1Id)!!.copy(accessionId = accessionId1))
    batchesDao.update(batchesDao.fetchOneById(species1Batch2Id)!!.copy(accessionId = accessionId2))

    val destinationFacilityId = insertFacility(type = FacilityType.Nursery, facilityNumber = 2)

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
                        germinatingQuantityWithdrawn = 0,
                        notReadyQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 1,
                        hardeningOffQuantityWithdrawn = 0),
                    BatchWithdrawalModel(
                        batchId = species1Batch2Id,
                        germinatingQuantityWithdrawn = 0,
                        notReadyQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 2,
                        hardeningOffQuantityWithdrawn = 0))))

    val newBatches =
        batchesDao.fetchByFacilityId(destinationFacilityId).associate {
          it.accessionId to it.readyQuantity
        }

    assertEquals(
        mapOf(accessionId1 to 1, accessionId2 to 2), newBatches, "Accession IDs of new batches")
  }

  @Test
  fun `nursery transfer adds to existing batch if batch number already exists`() {
    val species1Batch1 = batchesDao.fetchOneById(species1Batch1Id)!!

    val destinationTimeZone = ZoneId.of("Asia/Tokyo")
    val destinationFacilityId =
        insertFacility(
            type = FacilityType.Nursery, facilityNumber = 2, timeZone = destinationTimeZone)

    val newReadyByDate = LocalDate.of(2000, 1, 2)
    val firstWithdrawalTime = ZonedDateTime.of(2023, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
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
                            notReadyQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 3,
                            hardeningOffQuantityWithdrawn = 4))),
            newReadyByDate)

    val secondWithdrawalTime = ZonedDateTime.of(2023, 2, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
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
                            notReadyQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6,
                            hardeningOffQuantityWithdrawn = 7))),
            newReadyByDate)

    val newBatch = batchesDao.fetchByFacilityId(destinationFacilityId).first()

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(
                  germinatingQuantity = 10 - 1 - 4,
                  activeGrowthQuantity = 20 - 2 - 5,
                  readyQuantity = 30 - 3 - 6,
                  hardeningOffQuantity = 40 - 4 - 7,
                  totalLossCandidates = 20 + 30 + 40 - 2 - 5 - 3 - 6 - 4 - 7,
                  modifiedTime = secondWithdrawalTime,
                  version = 3,
              ),
              batchesDao.fetchOneById(species1Batch1Id),
              "Should have deducted withdrawn quantities from batch")
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
                      modifiedBy = user.userId,
                      modifiedTime = secondWithdrawalTime,
                      organizationId = organizationId,
                      readyByDate = newReadyByDate,
                      speciesId = speciesId,
                      germinatingQuantity = 1 + 4,
                      activeGrowthQuantity = 2 + 5,
                      readyQuantity = 3 + 6,
                      hardeningOffQuantity = 4 + 7,
                      latestObservedGerminatingQuantity = 1,
                      latestObservedActiveGrowthQuantity = 2,
                      latestObservedReadyQuantity = 3,
                      latestObservedHardeningOffQuantity = 4,
                      version = 2),
              ),
              batchesDao.fetchByFacilityId(destinationFacilityId),
              "Should have created one new batch")
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
                      withdrawalId = firstWithdrawal.id),
                  BatchWithdrawalsRow(
                      batchId = species1Batch1Id,
                      destinationBatchId = newBatch.id,
                      germinatingQuantityWithdrawn = 4,
                      activeGrowthQuantityWithdrawn = 5,
                      readyQuantityWithdrawn = 6,
                      hardeningOffQuantityWithdrawn = 7,
                      withdrawalId = secondWithdrawal.id),
              ),
              batchWithdrawalsDao.findAll().sortedBy { it.germinatingQuantityWithdrawn },
              "Should have created batch withdrawals")
        },
        {
          assertEquals(
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
                  ),
              ),
              batchQuantityHistoryDao.findAll().map { it.copy(id = null) }.toSet(),
              "Should have inserted quantity history rows")
        },
        {
          assertEquals(
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
                      destinationFacilityId = destinationFacilityId),
                  WithdrawalsRow(
                      id = secondWithdrawal.id,
                      facilityId = facilityId,
                      purposeId = WithdrawalPurpose.NurseryTransfer,
                      withdrawnDate = LocalDate.of(2022, 10, 2),
                      createdBy = user.userId,
                      createdTime = secondWithdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = secondWithdrawalTime,
                      destinationFacilityId = destinationFacilityId),
              ),
              nurseryWithdrawalsDao.findAll().toSet(),
              "Should have inserted withdrawals rows")
        })
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
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0)),
              destinationFacilityId = otherOrgFacilityId,
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.EPOCH))
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
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0,
                          hardeningOffQuantityWithdrawn = 0)),
              destinationFacilityId = destinationFacilityId,
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.EPOCH))
    }
  }
}
