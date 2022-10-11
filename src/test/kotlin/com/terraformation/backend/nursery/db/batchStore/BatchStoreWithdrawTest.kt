package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.nursery.db.BatchInventoryInsufficientException
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class BatchStoreWithdrawTest : BatchStoreTest() {
  private val speciesId2 = SpeciesId(2)
  private val species1Batch1Id = BatchId(11)
  private val species1Batch2Id = BatchId(12)
  private val species2Batch1Id = BatchId(21)

  @BeforeEach
  fun insertInitialBatches() {
    insertSpecies(speciesId2)
    insertBatch(
        id = species1Batch1Id,
        speciesId = speciesId,
        germinatingQuantity = 10,
        notReadyQuantity = 20,
        readyQuantity = 30)
    insertBatch(
        id = species1Batch2Id,
        speciesId = speciesId,
        germinatingQuantity = 40,
        notReadyQuantity = 50,
        readyQuantity = 60)
    insertBatch(
        id = species2Batch1Id,
        speciesId = speciesId2,
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
    every { clock.instant() } returns withdrawalTime

    val withdrawal =
        store.withdraw(
            NewWithdrawalModel(
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.Other,
                withdrawnDate = LocalDate.of(2022, 10, 1),
                destination = "Destination",
                reason = "Reason",
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
                      purposeId = WithdrawalPurpose.Other,
                      withdrawnDate = LocalDate.of(2022, 10, 1),
                      createdBy = user.userId,
                      createdTime = withdrawalTime,
                      modifiedBy = user.userId,
                      modifiedTime = withdrawalTime,
                      destination = "Destination",
                      reason = "Reason")),
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
    every { clock.instant() } returns withdrawalTime

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
  fun `throws exception if you try to create a nursery transfer withdrawal`() {
    assertThrows<IllegalArgumentException> {
      store.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId = species1Batch1Id,
                          germinatingQuantityWithdrawn = 1,
                          notReadyQuantityWithdrawn = 1,
                          readyQuantityWithdrawn = 1)),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.EPOCH))
    }
  }
}
