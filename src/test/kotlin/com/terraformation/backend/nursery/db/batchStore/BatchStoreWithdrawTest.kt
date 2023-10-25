package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
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
  private val species1Batch1Id = BatchId(11)
  private val species1Batch2Id = BatchId(12)
  private val species2Batch1Id = BatchId(21)

  private val destinationFacilityId = FacilityId(3)

  @BeforeEach
  fun insertInitialBatches() {
    speciesId2 = insertSpecies()
    insertBatch(
        id = species1Batch1Id,
        speciesId = speciesId,
        batchNumber = "21-2-1-011",
        germinatingQuantity = 10,
        notReadyQuantity = 20,
        readyQuantity = 30)
    insertBatch(
        id = species1Batch2Id,
        speciesId = speciesId,
        batchNumber = "21-2-1-012",
        germinatingQuantity = 40,
        notReadyQuantity = 50,
        readyQuantity = 60)
    insertBatch(
        id = species2Batch1Id,
        speciesId = speciesId2,
        batchNumber = "21-2-1-021",
        germinatingQuantity = 70,
        notReadyQuantity = 80,
        readyQuantity = 90)
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
                            readyQuantityWithdrawn = 3),
                        BatchWithdrawalModel(
                            batchId = species1Batch2Id,
                            germinatingQuantityWithdrawn = 4,
                            notReadyQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6),
                        BatchWithdrawalModel(
                            batchId = species2Batch1Id,
                            germinatingQuantityWithdrawn = 7,
                            notReadyQuantityWithdrawn = 8,
                            readyQuantityWithdrawn = 9))))

    assertAll(
        {
          assertEquals(
              listOf(
                  species1Batch1.copy(
                      germinatingQuantity = 9,
                      notReadyQuantity = 18,
                      readyQuantity = 27,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species1Batch2.copy(
                      germinatingQuantity = 36,
                      notReadyQuantity = 45,
                      readyQuantity = 54,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species2Batch1.copy(
                      germinatingQuantity = 63,
                      notReadyQuantity = 72,
                      readyQuantity = 81,
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
                  withdrawalId = withdrawal.id)

          assertEquals(
              listOf(
                  newHistoryRow.copy(
                      batchId = species1Batch1Id,
                      germinatingQuantity = 9,
                      notReadyQuantity = 18,
                      readyQuantity = 27),
                  newHistoryRow.copy(
                      batchId = species1Batch2Id,
                      germinatingQuantity = 36,
                      notReadyQuantity = 45,
                      readyQuantity = 54),
                  newHistoryRow.copy(
                      batchId = species2Batch1Id,
                      germinatingQuantity = 63,
                      notReadyQuantity = 72,
                      readyQuantity = 81)),
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
                            readyQuantityWithdrawn = 3))))

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(version = 2, modifiedTime = withdrawalTime),
              batchesDao.fetchOneById(species1Batch1Id),
              "Should not have deducted withdrawn quantities from batch")
        },
        {
          assertEquals(
              emptyList<BatchQuantityHistoryRow>(),
              batchQuantityHistoryDao.findAll(),
              "Should not have inserted quantity history row")
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
                      notReadyQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3)),
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
    insertTimeZone(newYorkZone)
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
                            readyQuantityWithdrawn = 3))))

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(
                  version = 2,
                  modifiedTime = withdrawalTime,
                  germinatingQuantity = 9,
                  notReadyQuantity = 18,
                  readyQuantity = 27,
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
                      notReadyQuantity = 18,
                      readyQuantity = 27)),
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
                      notReadyQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3)),
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
                        readyQuantityWithdrawn = 3))))

    assertEquals(
        initialSummary.copy(
            germinatingQuantity = initialSummary.germinatingQuantity - 1,
            notReadyQuantity = initialSummary.notReadyQuantity - 2,
            readyQuantity = initialSummary.readyQuantity - 3,
            lossRate = (5 * 100 / initialSummary.totalQuantity).toInt(),
            totalDead = 5,
            totalQuantity = initialSummary.totalQuantity - 5,
            totalWithdrawn = 5,
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
                          readyQuantityWithdrawn = 1),
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 0,
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 1))))
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
                          readyQuantityWithdrawn = 3000))))
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
                          readyQuantityWithdrawn = 1),
                      BatchWithdrawalModel(
                          batchId = species1Batch2Id,
                          germinatingQuantityWithdrawn = 1,
                          notReadyQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1),
                  ),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Dead,
              withdrawnDate = LocalDate.EPOCH))
    }
  }

  @Test
  fun `throws exception if any batches are not from requested facility ID`() {
    val otherFacilityId = FacilityId(2)
    insertFacility(otherFacilityId, type = FacilityType.Nursery)

    val otherFacilityBatchId =
        insertBatch(
            id = 100, facilityId = otherFacilityId, speciesId = speciesId, germinatingQuantity = 1)

    assertThrows<IllegalArgumentException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          notReadyQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1),
                      BatchWithdrawalModel(
                          batchId = otherFacilityBatchId,
                          germinatingQuantityWithdrawn = 1,
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0),
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

    insertFacility(destinationFacilityId, type = FacilityType.Nursery, facilityNumber = 2)

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
                            readyQuantityWithdrawn = 3),
                        BatchWithdrawalModel(
                            batchId = species1Batch2Id,
                            germinatingQuantityWithdrawn = 4,
                            notReadyQuantityWithdrawn = 5,
                            readyQuantityWithdrawn = 6),
                        BatchWithdrawalModel(
                            batchId = species2Batch1Id,
                            germinatingQuantityWithdrawn = 10,
                            notReadyQuantityWithdrawn = 11,
                            readyQuantityWithdrawn = 12))),
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
                      notReadyQuantity = 20 - 2,
                      readyQuantity = 30 - 3,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species1Batch2.copy(
                      germinatingQuantity = 40 - 4,
                      notReadyQuantity = 50 - 5,
                      readyQuantity = 60 - 6,
                      modifiedTime = withdrawalTime,
                      version = 2,
                  ),
                  species2Batch1.copy(
                      germinatingQuantity = 70 - 10,
                      notReadyQuantity = 80 - 11,
                      readyQuantity = 90 - 12,
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
                  latestObservedTime = withdrawalTime,
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
                      germinatingQuantity = 1,
                      notReadyQuantity = 2,
                      readyQuantity = 3,
                      latestObservedGerminatingQuantity = 1,
                      latestObservedNotReadyQuantity = 2,
                      latestObservedReadyQuantity = 3,
                      speciesId = speciesId,
                  ),
                  newBatch.copy(
                      batchNumber = "21-2-2-012",
                      id = newSpecies1Batch2.id!!,
                      germinatingQuantity = 4,
                      notReadyQuantity = 5,
                      readyQuantity = 6,
                      latestObservedGerminatingQuantity = 4,
                      latestObservedNotReadyQuantity = 5,
                      latestObservedReadyQuantity = 6,
                      speciesId = speciesId,
                  ),
                  newBatch.copy(
                      id = newSpecies2Batch.id!!,
                      batchNumber = "21-2-2-021",
                      germinatingQuantity = 10,
                      notReadyQuantity = 11,
                      readyQuantity = 12,
                      latestObservedGerminatingQuantity = 10,
                      latestObservedNotReadyQuantity = 11,
                      latestObservedReadyQuantity = 12,
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
                      notReadyQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3,
                      withdrawalId = withdrawal.id),
                  BatchWithdrawalsRow(
                      batchId = species1Batch2Id,
                      destinationBatchId = newSpecies1Batch2.id,
                      germinatingQuantityWithdrawn = 4,
                      notReadyQuantityWithdrawn = 5,
                      readyQuantityWithdrawn = 6,
                      withdrawalId = withdrawal.id),
                  BatchWithdrawalsRow(
                      batchId = species2Batch1Id,
                      destinationBatchId = newSpecies2Batch.id,
                      germinatingQuantityWithdrawn = 10,
                      notReadyQuantityWithdrawn = 11,
                      readyQuantityWithdrawn = 12,
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
                  createdTime = withdrawalTime)
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
                      notReadyQuantity = 2,
                      readyQuantity = 3),
                  destinationBatchHistoryRow.copy(
                      batchId = newSpecies1Batch2.id!!,
                      germinatingQuantity = 4,
                      notReadyQuantity = 5,
                      readyQuantity = 6),
                  originBatchHistoryRow.copy(
                      batchId = species1Batch1Id,
                      germinatingQuantity = 10 - 1,
                      notReadyQuantity = 20 - 2,
                      readyQuantity = 30 - 3),
                  destinationBatchHistoryRow.copy(
                      batchId = newSpecies2Batch.id!!,
                      germinatingQuantity = 10,
                      notReadyQuantity = 11,
                      readyQuantity = 12),
                  originBatchHistoryRow.copy(
                      batchId = species1Batch2Id,
                      germinatingQuantity = 40 - 4,
                      notReadyQuantity = 50 - 5,
                      readyQuantity = 60 - 6),
                  originBatchHistoryRow.copy(
                      batchId = species2Batch1Id,
                      germinatingQuantity = 70 - 10,
                      notReadyQuantity = 80 - 11,
                      readyQuantity = 90 - 12)),
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
  fun `nursery transfer adds to existing batch if batch number already exists`() {
    val species1Batch1 = batchesDao.fetchOneById(species1Batch1Id)!!

    insertFacility(destinationFacilityId, type = FacilityType.Nursery, facilityNumber = 2)

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
                            readyQuantityWithdrawn = 3))),
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
                            readyQuantityWithdrawn = 6))),
            newReadyByDate)

    val newBatch = batchesDao.fetchByFacilityId(destinationFacilityId).first()

    assertAll(
        {
          assertEquals(
              species1Batch1.copy(
                  germinatingQuantity = 10 - 1 - 4,
                  notReadyQuantity = 20 - 2 - 5,
                  readyQuantity = 30 - 3 - 6,
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
                      latestObservedTime = firstWithdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = secondWithdrawalTime,
                      organizationId = organizationId,
                      readyByDate = newReadyByDate,
                      speciesId = speciesId,
                      germinatingQuantity = 1 + 4,
                      notReadyQuantity = 2 + 5,
                      readyQuantity = 3 + 6,
                      latestObservedGerminatingQuantity = 1,
                      latestObservedNotReadyQuantity = 2,
                      latestObservedReadyQuantity = 3,
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
                      notReadyQuantityWithdrawn = 2,
                      readyQuantityWithdrawn = 3,
                      withdrawalId = firstWithdrawal.id),
                  BatchWithdrawalsRow(
                      batchId = species1Batch1Id,
                      destinationBatchId = newBatch.id,
                      germinatingQuantityWithdrawn = 4,
                      notReadyQuantityWithdrawn = 5,
                      readyQuantityWithdrawn = 6,
                      withdrawalId = secondWithdrawal.id),
              ),
              batchWithdrawalsDao.findAll().sortedBy { it.germinatingQuantityWithdrawn },
              "Should have created batch withdrawals")
        },
        {
          assertEquals(
              listOf(
                  BatchQuantityHistoryRow(
                      batchId = newBatch.id!!,
                      createdBy = user.userId,
                      createdTime = firstWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Observed,
                      germinatingQuantity = 1,
                      notReadyQuantity = 2,
                      readyQuantity = 3),
                  BatchQuantityHistoryRow(
                      batchId = newBatch.id!!,
                      createdBy = user.userId,
                      createdTime = secondWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Computed,
                      germinatingQuantity = 1 + 4,
                      notReadyQuantity = 2 + 5,
                      readyQuantity = 3 + 6),
                  BatchQuantityHistoryRow(
                      batchId = species1Batch1Id,
                      createdBy = user.userId,
                      createdTime = secondWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Computed,
                      germinatingQuantity = 10 - 1 - 4,
                      notReadyQuantity = 20 - 2 - 5,
                      readyQuantity = 30 - 3 - 6,
                      withdrawalId = secondWithdrawal.id),
                  BatchQuantityHistoryRow(
                      batchId = species1Batch1Id,
                      createdBy = user.userId,
                      createdTime = firstWithdrawalTime,
                      historyTypeId = BatchQuantityHistoryType.Computed,
                      germinatingQuantity = 10 - 1,
                      notReadyQuantity = 20 - 2,
                      readyQuantity = 30 - 3,
                      withdrawalId = firstWithdrawal.id),
              ),
              batchQuantityHistoryDao
                  .findAll()
                  .map { it.copy(id = null) }
                  .sortedBy { it.readyQuantity!! },
              "Should have inserted quantity history rows")
        },
        {
          assertEquals(
              listOf(
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
              nurseryWithdrawalsDao.findAll(),
              "Should have inserted withdrawals rows")
        })
  }

  @Test
  fun `throws exception if destination facility is in a different organization`() {
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(3)

    insertOrganization(otherOrganizationId)
    insertFacility(otherOrgFacilityId, otherOrganizationId, type = FacilityType.Nursery)

    assertThrows<CrossOrganizationNurseryTransferNotAllowedException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 0)),
              destinationFacilityId = otherOrgFacilityId,
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.EPOCH))
    }
  }

  @Test
  fun `throws exception if no permission to create batches at destination facility`() {
    insertFacility(destinationFacilityId, type = FacilityType.Nursery)

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
                          readyQuantityWithdrawn = 0)),
              destinationFacilityId = destinationFacilityId,
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.EPOCH))
    }
  }
}
