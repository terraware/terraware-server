package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BatchStoreCalculationsTest : BatchStoreTest() {
  private lateinit var destinationFacilityId: FacilityId

  @BeforeEach
  fun insertOtherNursery() {
    destinationFacilityId = insertFacility(type = FacilityType.Nursery)
  }

  @Test
  fun `calculates germination and loss rate if all withdrawn and no manual edits`() {
    runScenario(
        initial = Quantities(100, 15, 15),
        transfer = Quantities(12, 10, 0),
        dead = Quantities(10, 6, 10),
        other = Quantities(0, 0, 10),
        current = Quantities(0, 45, 27),
        expectedGerminationRate = 89,
        expectedLossRate = 18)
  }

  @Test
  fun `calculates loss rate but not germination rate if germinating seedlings still present`() {
    runScenario(
        initial = Quantities(100, 25, 25),
        outPlant = Quantities(0, 0, 23),
        transfer = Quantities(13, 0, 0),
        dead = Quantities(10, 6, 10),
        other = Quantities(0, 8, 7),
        current = Quantities(1, 45, 27),
        expectedGerminationRate = null,
        expectedLossRate = 14)
  }

  @Test
  fun `calculates loss rate but not germination rate if germinating quantity has been edited`() {
    runScenario(
        initial = Quantities(100, 15, 15),
        transfer = Quantities(13, 10, 0),
        dead = Quantities(10, 6, 10),
        other = Quantities(0, 0, 12),
        manualEdits = Quantities(3, 0, 0),
        current = Quantities(0, 45, 27),
        expectedGerminationRate = null,
        expectedLossRate = 18)
  }

  @Test
  fun `does not calculate either rate if not ready quantity has been edited`() {
    runScenario(
        initial = Quantities(100, 15, 15),
        transfer = Quantities(13, 10, 0),
        dead = Quantities(10, 6, 10),
        other = Quantities(0, 0, 12),
        manualEdits = Quantities(0, 3, 0),
        current = Quantities(0, 45, 27),
        expectedGerminationRate = null,
        expectedLossRate = null)
  }

  @Test
  fun `loss rate is 0 at initial creation if there are non-germinating seedlings`() {
    runScenario(
        initial = Quantities(10, 10, 10),
        current = Quantities(10, 10, 10),
        expectedGerminationRate = null,
        expectedLossRate = 0)
  }

  @Test
  fun `loss rate is not calculated at initial creation if there are only germinating seedlings`() {
    runScenario(
        initial = Quantities(10, 0, 0),
        current = Quantities(10, 0, 0),
        expectedGerminationRate = null,
        expectedLossRate = null)
  }

  @Test
  fun `removes existing rates if quantities are manually edited`() {
    val batchId =
        runScenario(
            initial = Quantities(10, 10, 10),
            current = Quantities(0, 20, 10),
            expectedGerminationRate = 100,
            expectedLossRate = 0)

    addManualEdits(batchId, Quantities(0, 1, 0))

    assertNull(store.fetchOneById(batchId).germinationRate, "Germination rate after manual edit")
    assertNull(store.fetchOneById(batchId).lossRate, "Loss rate after manual edit")
  }

  @Test
  fun `removes existing rates if more seedlings added via nursery transfer`() {
    val sourceBatchId = createBatch(Quantities(100, 100, 100))
    val withdrawal = addTransfer(sourceBatchId, Quantities(10, 10, 10))
    val batchId = withdrawal.batchWithdrawals.first().destinationBatchId!!

    store.changeStatuses(batchId, 10, 0)

    val beforeTransfer = store.fetchOneById(batchId)
    assertEquals(100, beforeTransfer.germinationRate, "Germination rate before second transfer")
    assertEquals(0, beforeTransfer.lossRate, "Loss rate before second transfer")

    addTransfer(sourceBatchId, Quantities(0, 1, 0))

    val afterTransfer = store.fetchOneById(batchId)
    assertNull(afterTransfer.germinationRate, "Germination rate after second transfer")
    assertNull(afterTransfer.lossRate, "Loss rate after second transfer")
  }

  @Test
  fun `germination rate not calculated if all germinating seedlings withdrawn`() {
    runScenario(
        initial = Quantities(10, 1, 0),
        other = Quantities(10, 0, 0),
        current = Quantities(0, 1, 0),
        expectedGerminationRate = null,
        expectedLossRate = 0)
  }

  /**
   * Runs a scenario that consists of a canned sequence of operations. This is to support running
   * the example cases listed in the calculations spreadsheet that's part of the requirements doc.
   *
   * @param current The quantities we want to end up with after all the operations are done. This
   *   method will generate the necessary status changes to arrive at these numbers, if possible.
   * @param manualEdits The changes to make to the initial quantities. The numbers here are treated
   *   as deltas, not as absolute values.
   */
  private fun runScenario(
      initial: Quantities,
      outPlant: Quantities? = null,
      transfer: Quantities? = null,
      dead: Quantities? = null,
      other: Quantities? = null,
      manualEdits: Quantities? = null,
      current: Quantities,
      expectedGerminationRate: Int? = null,
      expectedLossRate: Int? = null,
  ): BatchId {
    val operations = listOfNotNull(outPlant, transfer, dead, other)

    // Figure out what status change operations are required in order to end up with the requested
    // "current" quantities after all the other operations are done.
    val germinatingChangeNeeded =
        initial.germinating + (manualEdits?.germinating ?: 0) -
            operations.sumOf { it.germinating } -
            current.germinating
    val notReadyChangeNeeded =
        initial.notReady + (manualEdits?.notReady ?: 0) -
            operations.sumOf { it.notReady } -
            current.notReady + germinatingChangeNeeded

    assertTrue(
        germinatingChangeNeeded >= 0 && notReadyChangeNeeded >= 0,
        "Cannot arrive at current numbers given initial and withdrawal quantities")

    val batchId = createBatch(initial)

    manualEdits?.let { addManualEdits(batchId, it) }

    // Do this in two steps to mimic how the web app would behave.
    store.changeStatuses(batchId, germinatingChangeNeeded, 0)
    store.changeStatuses(batchId, 0, notReadyChangeNeeded)

    outPlant?.let { store.withdraw(newWithdrawalModel(batchId, it, WithdrawalPurpose.OutPlant)) }
    transfer?.let { addTransfer(batchId, it) }
    dead?.let { store.withdraw(newWithdrawalModel(batchId, it, WithdrawalPurpose.Dead)) }
    other?.let { store.withdraw(newWithdrawalModel(batchId, it, WithdrawalPurpose.Other)) }

    val result = store.fetchOneById(batchId)

    assertEquals(current.germinating, result.germinatingQuantity, "Current germinating quantity")
    assertEquals(current.notReady, result.notReadyQuantity, "Current not ready quantity")
    assertEquals(current.ready, result.readyQuantity, "Current ready quantity")
    assertEquals(expectedGerminationRate, result.germinationRate, "Germination rate")
    assertEquals(expectedLossRate, result.lossRate, "Loss rate")

    return batchId
  }

  private fun createBatch(initial: Quantities): BatchId {
    return store
        .create(
            NewBatchModel(
                addedDate = LocalDate.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = initial.germinating,
                notReadyQuantity = initial.notReady,
                readyQuantity = initial.ready,
                speciesId = speciesId,
            ),
        )
        .id
  }

  private fun addTransfer(batchId: BatchId, quantities: Quantities): ExistingWithdrawalModel {
    return store.withdraw(
        newWithdrawalModel(
            batchId, quantities, WithdrawalPurpose.NurseryTransfer, destinationFacilityId))
  }

  private fun addManualEdits(batchId: BatchId, quantityDeltas: Quantities) {
    val batch = store.fetchOneById(batchId)

    store.updateQuantities(
        batchId,
        batch.version,
        batch.germinatingQuantity + quantityDeltas.germinating,
        batch.notReadyQuantity + quantityDeltas.notReady,
        batch.readyQuantity + quantityDeltas.ready,
        BatchQuantityHistoryType.Observed)
  }

  private fun newWithdrawalModel(
      batchId: BatchId,
      quantities: Quantities,
      purpose: WithdrawalPurpose,
      destinationFacilityId: FacilityId? = null,
  ): NewWithdrawalModel {
    return NewWithdrawalModel(
        batchWithdrawals =
            listOf(
                BatchWithdrawalModel(
                    batchId = batchId,
                    germinatingQuantityWithdrawn = quantities.germinating,
                    notReadyQuantityWithdrawn = quantities.notReady,
                    readyQuantityWithdrawn = quantities.ready)),
        destinationFacilityId = destinationFacilityId,
        facilityId = facilityId,
        id = null,
        purpose = purpose,
        withdrawnDate = LocalDate.EPOCH,
    )
  }

  private class Quantities(val germinating: Int, val notReady: Int, val ready: Int)
}
