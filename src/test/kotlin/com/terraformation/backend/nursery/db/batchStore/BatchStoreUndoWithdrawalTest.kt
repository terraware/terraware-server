package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.nursery.db.UndoOfNurseryTransferNotAllowedException
import com.terraformation.backend.nursery.db.UndoOfUndoNotAllowedException
import com.terraformation.backend.nursery.db.WithdrawalAlreadyUndoneException
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import io.mockk.every
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class BatchStoreUndoWithdrawalTest : BatchStoreTest() {
  private lateinit var batch1Id: BatchId
  private lateinit var batch2Id: BatchId

  @BeforeEach
  fun insertInitialBatches() {
    batch1Id =
        insertBatch(
            speciesId = speciesId,
            batchNumber = "21-2-1-011",
            germinatingQuantity = 10,
            notReadyQuantity = 20,
            readyQuantity = 30,
            hardeningOffQuantity = 40,
            totalLost = 0,
            totalLossCandidates = 20 + 30 + 40)
    batch2Id =
        insertBatch(
            speciesId = speciesId,
            batchNumber = "21-2-1-012",
            germinatingQuantity = 40,
            notReadyQuantity = 50,
            readyQuantity = 60,
            hardeningOffQuantity = 70,
            totalLost = 0,
            totalLossCandidates = 50 + 60 + 70)

    every { user.canReadWithdrawal(any()) } returns true
  }

  @Test
  fun `updates quantities of batches without more recent observed quantities than the original withdrawal date`() {
    val batch1BeforeWithdrawal = store.fetchOneById(batch1Id)

    clock.instant = clock.instant.plus(2, ChronoUnit.DAYS)

    val withdrawalId =
        makeInitialWithdrawal(
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(batch1Id, null, 1, 4, 2, 3),
                    BatchWithdrawalModel(batch2Id, null, 4, 7, 5, 6)))

    clock.instant = clock.instant.plus(2, ChronoUnit.DAYS)

    store.updateQuantities(
        batchId = batch2Id,
        version = 2,
        germinating = 100,
        notReady = 110,
        ready = 120,
        hardeningOff = 130,
        historyType = BatchQuantityHistoryType.Observed)

    val batch2AfterQuantityEdit = store.fetchOneById(batch2Id)

    clock.instant = clock.instant.plus(2, ChronoUnit.DAYS)

    val undoWithdrawal = store.undoWithdrawal(withdrawalId)

    assertEquals(
        ExistingWithdrawalModel(
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(batch1Id, null, -1, -4, -2, -3),
                    BatchWithdrawalModel(batch2Id, null, -4, -7, -5, -6),
                ),
            facilityId = facilityId,
            id = undoWithdrawal.id,
            purpose = WithdrawalPurpose.Undo,
            withdrawnDate = LocalDate.ofInstant(clock.instant, ZoneOffset.UTC),
            undoesWithdrawalId = withdrawalId,
        ),
        undoWithdrawal)

    val batch1AfterUndo = store.fetchOneById(batch1Id)
    val batch2AfterUndo = store.fetchOneById(batch2Id)

    assertEquals(
        batch1BeforeWithdrawal.copy(version = 3),
        batch1AfterUndo,
        "Should have updated batch without recent observed quantities")
    assertEquals(
        batch2AfterQuantityEdit.copy(totalWithdrawn = 0, version = 4),
        batch2AfterUndo,
        "Should not have updated batch with recent observed quantities")

    val quantityHistories = batchQuantityHistoryDao.findAll().onEach { it.id = null }

    assertEquals(
        listOf(
            BatchQuantityHistoryRow(
                batchId = batch1Id,
                historyTypeId = BatchQuantityHistoryType.Computed,
                createdBy = user.userId,
                createdTime = clock.instant,
                germinatingQuantity = 10,
                activeGrowthQuantity = 20,
                readyQuantity = 30,
                hardeningOffQuantity = 40,
                withdrawalId = undoWithdrawal.id,
                version = 3,
            )),
        quantityHistories.filter { it.batchId == batch1Id && it.withdrawalId == undoWithdrawal.id },
        "Should have created quantity history entry for undo on batch whose quantity was updated")

    assertEquals(
        emptyList<BatchQuantityHistoryRow>(),
        quantityHistories.filter { it.batchId == batch2Id && it.withdrawalId == undoWithdrawal.id },
        "Should not have created quantity history entry for undo on batch with more recent observed quantity")
  }

  @Test
  fun `throws exception if undoing nursery transfer`() {
    val otherFacilityId = insertFacility(type = FacilityType.Nursery)

    val withdrawalId =
        makeInitialWithdrawal(
            destinationFacilityId = otherFacilityId, purpose = WithdrawalPurpose.NurseryTransfer)

    assertThrows<UndoOfNurseryTransferNotAllowedException> { store.undoWithdrawal(withdrawalId) }
  }

  @Test
  fun `throws exception if undoing a withdrawal that has already been undone`() {
    val withdrawalId = makeInitialWithdrawal()
    store.undoWithdrawal(withdrawalId)

    assertThrows<WithdrawalAlreadyUndoneException> { store.undoWithdrawal(withdrawalId) }
  }

  @Test
  fun `throws exception if undoing an undo withdrawal`() {
    val undoWithdrawal = store.undoWithdrawal(makeInitialWithdrawal())

    assertThrows<UndoOfUndoNotAllowedException> { store.undoWithdrawal(undoWithdrawal.id) }
  }

  @Test
  fun `throws exception if no permission to update batches`() {
    val withdrawalId = makeInitialWithdrawal()
    store.undoWithdrawal(withdrawalId)

    every { user.canUpdateBatch(any()) } returns false

    assertThrows<AccessDeniedException> { store.undoWithdrawal(withdrawalId) }
  }

  private fun makeInitialWithdrawal(
      batchWithdrawals: List<BatchWithdrawalModel> =
          listOf(
              BatchWithdrawalModel(
                  batchId = batch1Id,
                  germinatingQuantityWithdrawn = 1,
                  notReadyQuantityWithdrawn = 2,
                  readyQuantityWithdrawn = 3,
                  hardeningOffQuantityWithdrawn = 4)),
      destinationFacilityId: FacilityId? = null,
      purpose: WithdrawalPurpose = WithdrawalPurpose.Other,
      withdrawnDate: LocalDate = LocalDate.EPOCH,
  ): WithdrawalId {
    return store
        .withdraw(
            NewWithdrawalModel(
                batchWithdrawals = batchWithdrawals,
                destinationFacilityId = destinationFacilityId,
                facilityId = facilityId,
                id = null,
                purpose = purpose,
                withdrawnDate = withdrawnDate))
        .id
  }
}
