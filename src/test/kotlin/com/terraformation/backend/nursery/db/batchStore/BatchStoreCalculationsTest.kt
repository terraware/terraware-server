package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.records.BatchStageDetailsRecord
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.nursery.model.NurseryBatchPhase
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
    every { user.canReadWithdrawal(any()) } returns true
  }

  @Test
  fun `calculates germination and loss rate if all withdrawn and no manual edits`() {
    runScenario(
        initial = Quantities(100, 15, 0, 15),
        transfer = Quantities(12, 10, 0, 0),
        dead = Quantities(10, 6, 0, 10),
        other = Quantities(0, 0, 0, 10),
        current = Quantities(0, 45, 0, 27),
        expectedGerminationRate = 89,
        expectedLossRate = 18,
    )
  }

  @Test
  fun `calculates loss rate but not germination rate if germinating seedlings still present`() {
    runScenario(
        initial = Quantities(100, 25, 0, 25),
        outPlant = Quantities(0, 0, 0, 23),
        transfer = Quantities(13, 0, 0, 0),
        dead = Quantities(10, 6, 0, 10),
        other = Quantities(0, 8, 0, 7),
        current = Quantities(1, 45, 0, 27),
        expectedGerminationRate = null,
        expectedLossRate = 14,
    )
  }

  @Test
  fun `calculates loss rate but not germination rate if germinating quantity has been edited`() {
    runScenario(
        initial = Quantities(100, 15, 0, 15),
        transfer = Quantities(13, 10, 0, 0),
        dead = Quantities(10, 6, 0, 10),
        other = Quantities(0, 0, 0, 12),
        manualEdits = Quantities(3, 0, 0, 0),
        current = Quantities(0, 45, 0, 27),
        expectedGerminationRate = null,
        expectedLossRate = 18,
    )
  }

  @Test
  fun `does not calculate either rate if active growth quantity has been edited`() {
    runScenario(
        initial = Quantities(100, 15, 0, 15),
        transfer = Quantities(13, 10, 0, 0),
        dead = Quantities(10, 6, 0, 10),
        other = Quantities(0, 0, 0, 12),
        manualEdits = Quantities(0, 3, 0, 0),
        current = Quantities(0, 45, 0, 27),
        expectedGerminationRate = null,
        expectedLossRate = null,
    )
  }

  @Test
  fun `loss rate is 0 at initial creation if there are non-germinating seedlings`() {
    runScenario(
        initial = Quantities(10, 10, 0, 10),
        current = Quantities(10, 10, 0, 10),
        expectedGerminationRate = null,
        expectedLossRate = 0,
    )
  }

  @Test
  fun `loss rate is not calculated at initial creation if there are only germinating seedlings`() {
    runScenario(
        initial = Quantities(10, 0, 0, 0),
        current = Quantities(10, 0, 0, 0),
        expectedGerminationRate = null,
        expectedLossRate = null,
    )
  }

  @Test
  fun `removes existing rates if quantities are manually edited`() {
    val batchId =
        runScenario(
            initial = Quantities(10, 10, 0, 10),
            current = Quantities(0, 20, 0, 10),
            expectedGerminationRate = 100,
            expectedLossRate = 0,
        )

    addManualEdits(batchId, Quantities(0, 1, 0, 0))

    assertNull(store.fetchOneById(batchId).germinationRate, "Germination rate after manual edit")
    assertNull(store.fetchOneById(batchId).lossRate, "Loss rate after manual edit")
  }

  @Test
  fun `hardening off quantity affects both rates`() {
    runScenario(
        initial = Quantities(100, 0, 15, 15),
        transfer = Quantities(12, 0, 10, 0),
        dead = Quantities(10, 0, 6, 10),
        other = Quantities(0, 0, 0, 10),
        current = Quantities(0, 0, 45, 27),
        expectedGerminationRate = 89,
        expectedLossRate = 18,
    )
  }

  @Test
  fun `all phase changes are taken into account when calculating rates`() {
    runScenario(
        initial = Quantities(100, 30, 15, 15),
        transfer = Quantities(12, 8, 10, 0),
        dead = Quantities(10, 11, 5, 10),
        other = Quantities(0, 3, 0, 10),
        current = Quantities(0, 14, 45, 22),
        expectedGerminationRate = 89,
        expectedLossRate = 24,
    )
  }

  @Test
  fun `updates rates if more seedlings added via nursery transfer`() {
    val sourceBatchId = createBatch(Quantities(100, 100, 0, 100))
    val withdrawal = addTransfer(sourceBatchId, Quantities(10, 10, 0, 10))
    val batchId = withdrawal.batchWithdrawals.first().destinationBatchId!!

    store.changeStatuses(batchId, NurseryBatchPhase.Germinating, NurseryBatchPhase.ActiveGrowth, 6)
    store.withdraw(
        newWithdrawalModel(
            batchId,
            Quantities(4, 1, 0, 0),
            WithdrawalPurpose.Dead,
            facilityId = destinationFacilityId,
        )
    )

    val beforeTransfer = store.fetchOneById(batchId)
    assertEquals(60, beforeTransfer.germinationRate, "Germination rate before second transfer")
    assertEquals(4, beforeTransfer.lossRate, "Loss rate before second transfer")

    addTransfer(sourceBatchId, Quantities(1, 5, 0, 0))
    store.changeStatuses(batchId, NurseryBatchPhase.Germinating, NurseryBatchPhase.ActiveGrowth, 1)

    val afterTransfer = store.fetchOneById(batchId)
    assertEquals(64, afterTransfer.germinationRate, "Germination rate after second transfer")
    assertEquals(3, afterTransfer.lossRate, "Loss rate after second transfer")
  }

  @Test
  fun `germination rate not calculated if all germinating seedlings withdrawn`() {
    runScenario(
        initial = Quantities(10, 1, 0, 0),
        other = Quantities(10, 0, 0, 0),
        current = Quantities(0, 1, 0, 0),
        expectedGerminationRate = null,
        expectedLossRate = 0,
    )
  }

  @Test
  fun `treats subsequent additions from accessions as part of initial quantity`() {
    runScenario(
        initial = Quantities(100, 30, 15, 15),
        transfer = Quantities(12, 8, 10, 0),
        dead = Quantities(10, 11, 5, 10),
        other = Quantities(0, 3, 0, 10),
        addedFromAccession = Quantities(100, 0, 0, 0),
        current = Quantities(0, 37, 93, 51),
        expectedGerminationRate = 95,
        expectedLossRate = 13,
    )
  }

  @Test
  fun `replays stage time after withdrawals, status changes, undo, and manual edits`() {
    val start = LocalDate.of(2023, 1, 1)
    val created = LocalDate.of(2023, 1, 11)
    val deadDate = LocalDate.of(2023, 1, 16)
    val stageChangeDate = LocalDate.of(2023, 2, 1)
    val outplantDate = LocalDate.of(2023, 2, 6)
    val undoDate = LocalDate.of(2023, 3, 11)
    val asOf = LocalDate.of(2023, 3, 16).atStartOfDay(ZoneOffset.UTC).toInstant()

    clock.instant = created.atStartOfDay(ZoneOffset.UTC).toInstant()
    val batchId =
        store
            .create(
                NewBatchModel(
                    activeGrowthQuantity = 0,
                    addedDate = start,
                    facilityId = facilityId,
                    germinatingQuantity = 100,
                    hardeningOffQuantity = 0,
                    readyQuantity = 0,
                    seedsSownDate = start,
                    speciesId = speciesId,
                )
            )
            .id

    clock.instant = LocalDate.of(2023, 1, 31).atStartOfDay(ZoneOffset.UTC).toInstant()
    store.withdraw(
        NewWithdrawalModel(
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        activeGrowthQuantityWithdrawn = 0,
                        batchId = batchId,
                        germinatingQuantityWithdrawn = 20,
                        hardeningOffQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 0,
                    )
                ),
            facilityId = facilityId,
            id = null,
            purpose = WithdrawalPurpose.Dead,
            withdrawnDate = deadDate,
        )
    )

    clock.instant = stageChangeDate.atStartOfDay(ZoneOffset.UTC).toInstant()
    store.changeStatuses(batchId, NurseryBatchPhase.Germinating, NurseryBatchPhase.Ready, 79)

    clock.instant = LocalDate.of(2023, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
    val outplant =
        store.withdraw(
            NewWithdrawalModel(
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            activeGrowthQuantityWithdrawn = 0,
                            batchId = batchId,
                            germinatingQuantityWithdrawn = 0,
                            hardeningOffQuantityWithdrawn = 0,
                            readyQuantityWithdrawn = 30,
                        )
                    ),
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.OutPlant,
                withdrawnDate = outplantDate,
            )
        )

    clock.instant = undoDate.atStartOfDay(ZoneOffset.UTC).toInstant()
    store.undoWithdrawal(outplant.id)

    clock.instant = asOf
    store.updateQuantities(
        batchId,
        store.fetchOneById(batchId).version,
        0,
        0,
        0,
        0,
        BatchQuantityHistoryType.Observed,
    )

    assertTableEquals(
        listOf(
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = 79,
                countedSeedlingDays = 2449.0,
                latestObservedQuantity = 0,
                openSeedlingDays = 0.0,
                quantity = 0,
                stageId = BatchStage.Germinating,
                timeInStageAsOf = asOf,
                timeInStageCleared = true,
            ),
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = 0,
                countedSeedlingDays = 0.0,
                latestObservedQuantity = 0,
                openSeedlingDays = 0.0,
                quantity = 0,
                stageId = BatchStage.ActiveGrowth,
                timeInStageAsOf = asOf,
                timeInStageCleared = false,
            ),
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = 0,
                countedSeedlingDays = 0.0,
                latestObservedQuantity = 0,
                openSeedlingDays = 0.0,
                quantity = 0,
                stageId = BatchStage.HardeningOff,
                timeInStageAsOf = asOf,
                timeInStageCleared = false,
            ),
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = 0,
                countedSeedlingDays = 0.0,
                latestObservedQuantity = 0,
                openSeedlingDays = 0.0,
                quantity = 0,
                stageId = BatchStage.Ready,
                timeInStageAsOf = asOf,
                timeInStageCleared = true,
            ),
        ),
        message = "Replayed stage times after withdrawals, status changes, undo, and manual edit",
    )
  }

  @Test
  fun `omits active growth time for batches created before hardening off release`() {
    val activeGrowthStartedAt = Instant.parse("2025-08-29T00:00:00Z")
    val asOf = Instant.parse("2025-09-01T00:00:00Z")

    fun createBatch(createdTime: Instant): BatchId {
      clock.instant = createdTime
      val addedDate = createdTime.atZone(ZoneOffset.UTC).toLocalDate()
      return store
          .create(
              NewBatchModel(
                  activeGrowthQuantity = 0,
                  addedDate = addedDate,
                  facilityId = facilityId,
                  germinatingQuantity = 5,
                  hardeningOffQuantity = 0,
                  readyQuantity = 0,
                  seedsSownDate = LocalDate.of(2025, 8, 26),
                  speciesId = speciesId,
              )
          )
          .id
    }

    fun moveSeedlingsToReady(batchId: BatchId) {
      clock.instant = activeGrowthStartedAt
      store.changeStatuses(
          batchId,
          NurseryBatchPhase.Germinating,
          NurseryBatchPhase.ActiveGrowth,
          5,
      )
      clock.instant = asOf
      store.changeStatuses(batchId, NurseryBatchPhase.ActiveGrowth, NurseryBatchPhase.Ready, 5)
    }

    val legacyBatchId = createBatch(Instant.parse("2025-08-28T19:55:59Z"))
    moveSeedlingsToReady(legacyBatchId)
    val currentBatchId = createBatch(Instant.parse("2025-08-28T19:56:00Z"))
    moveSeedlingsToReady(currentBatchId)

    fun expectedStageDetails(
        batchId: BatchId,
        stage: BatchStage,
        latestObservedQuantity: Int,
        quantity: Int,
        countedQuantity: Int,
        countedDays: Double,
        openDays: Double,
    ) =
        BatchStageDetailsRecord(
            batchId = batchId,
            countedQuantity = countedQuantity,
            countedSeedlingDays = countedDays,
            latestObservedQuantity = latestObservedQuantity,
            openSeedlingDays = openDays,
            quantity = quantity,
            stageId = stage,
            timeInStageAsOf = asOf,
            timeInStageCleared = false,
        )

    assertTableEquals(
        listOf(
            expectedStageDetails(legacyBatchId, BatchStage.Germinating, 5, 0, 5, 15.0, 0.0),
            expectedStageDetails(legacyBatchId, BatchStage.ActiveGrowth, 0, 0, 0, 0.0, 0.0),
            expectedStageDetails(legacyBatchId, BatchStage.HardeningOff, 0, 0, 0, 0.0, 0.0),
            expectedStageDetails(legacyBatchId, BatchStage.Ready, 0, 5, 5, 0.0, 0.0),
            expectedStageDetails(currentBatchId, BatchStage.Germinating, 5, 0, 5, 15.0, 0.0),
            expectedStageDetails(currentBatchId, BatchStage.ActiveGrowth, 0, 0, 5, 15.0, 0.0),
            expectedStageDetails(currentBatchId, BatchStage.HardeningOff, 0, 0, 0, 0.0, 0.0),
            expectedStageDetails(currentBatchId, BatchStage.Ready, 0, 5, 5, 0.0, 0.0),
        ),
        message = "Active Growth time-in-stage stats respect the Hardening Off release date",
    )
  }

  @Test
  fun `does not calculate stage time when withdrawal history has a quantity gap`() {
    val historyTime = LocalDate.of(2023, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
    val batchId =
        insertBatch(
            activeGrowthQuantity = 17,
            germinatingQuantity = 7,
            hardeningOffQuantity = 26,
            readyQuantity = 35,
            version = 2,
        )
    val withdrawalId =
        insertNurseryWithdrawal(
            createdTime = historyTime,
            purpose = WithdrawalPurpose.Dead,
            withdrawnDate = LocalDate.of(2023, 1, 1),
        )
    insertBatchWithdrawal(
        activeGrowthQuantityWithdrawn = 3,
        batchId = batchId,
        germinatingQuantityWithdrawn = 2,
        hardeningOffQuantityWithdrawn = 4,
        readyQuantityWithdrawn = 5,
        withdrawalId = withdrawalId,
    )
    insertBatchQuantityHistory(
        activeGrowthQuantity = 20,
        batchId = batchId,
        createdTime = historyTime,
        effectiveTime = historyTime,
        germinatingQuantity = 10,
        hardeningOffQuantity = 30,
        historyType = BatchQuantityHistoryType.Observed,
        readyQuantity = 40,
        version = 1,
    )
    insertBatchQuantityHistory(
        activeGrowthQuantity = 17,
        batchId = batchId,
        createdTime = historyTime.plusSeconds(1),
        effectiveTime = historyTime.plusSeconds(1),
        germinatingQuantity = 7,
        hardeningOffQuantity = 26,
        historyType = BatchQuantityHistoryType.Computed,
        readyQuantity = 35,
        version = 2,
        withdrawalId = withdrawalId,
    )

    store.updateQuantities(
        batchId,
        activeGrowth = 17,
        germinating = 6,
        hardeningOff = 26,
        historyType = BatchQuantityHistoryType.Observed,
        ready = 35,
        version = 2,
    )

    assertTableEquals(
        listOf(
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = null,
                countedSeedlingDays = null,
                latestObservedQuantity = 6,
                openSeedlingDays = null,
                quantity = 6,
                stageId = BatchStage.Germinating,
                timeInStageAsOf = null,
                timeInStageCleared = null,
            ),
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = null,
                countedSeedlingDays = null,
                latestObservedQuantity = 17,
                openSeedlingDays = null,
                quantity = 17,
                stageId = BatchStage.ActiveGrowth,
                timeInStageAsOf = null,
                timeInStageCleared = null,
            ),
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = null,
                countedSeedlingDays = null,
                latestObservedQuantity = 26,
                openSeedlingDays = null,
                quantity = 26,
                stageId = BatchStage.HardeningOff,
                timeInStageAsOf = null,
                timeInStageCleared = null,
            ),
            BatchStageDetailsRecord(
                batchId = batchId,
                countedQuantity = null,
                countedSeedlingDays = null,
                latestObservedQuantity = 35,
                openSeedlingDays = null,
                quantity = 35,
                stageId = BatchStage.Ready,
                timeInStageAsOf = null,
                timeInStageCleared = null,
            ),
        ),
        message = "Stage stats remain null after a withdrawal history gap",
    )
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
      addedFromAccession: Quantities? = null,
      manualEdits: Quantities? = null,
      current: Quantities,
      expectedGerminationRate: Int? = null,
      expectedLossRate: Int? = null,
  ): BatchId {
    val operations = listOfNotNull(outPlant, transfer, dead, other)

    // Figure out what status change operations are required in order to end up with the requested
    // "current" quantities after all the other operations are done.
    val initialValueSources = listOfNotNull(initial, manualEdits, addedFromAccession)
    val germinatingChangeNeeded =
        initialValueSources.sumOf { it.germinating } -
            operations.sumOf { it.germinating } -
            current.germinating
    val activeGrowthChangeNeeded =
        initialValueSources.sumOf { it.activeGrowth } -
            operations.sumOf { it.activeGrowth } -
            current.activeGrowth + germinatingChangeNeeded
    val hardeningOffChangeNeeded =
        initialValueSources.sumOf { it.hardeningOff } -
            operations.sumOf { it.hardeningOff } -
            current.hardeningOff + activeGrowthChangeNeeded

    assertTrue(
        germinatingChangeNeeded >= 0 &&
            activeGrowthChangeNeeded >= 0 &&
            hardeningOffChangeNeeded >= 0,
        "Cannot arrive at current numbers given initial and withdrawal quantities",
    )

    val batchId = createBatch(initial)

    addedFromAccession?.let {
      store.addToExistingBatch(
          accessionId = insertAccession(),
          addedDate = LocalDate.EPOCH,
          batchId = batchId,
          germinatingQuantity = it.germinating,
      )
    }
    manualEdits?.let { addManualEdits(batchId, it) }

    // Do this in three steps to mimic how the web app would behave.
    store.changeStatuses(
        batchId,
        NurseryBatchPhase.Germinating,
        NurseryBatchPhase.ActiveGrowth,
        germinatingChangeNeeded,
    )
    store.changeStatuses(
        batchId,
        NurseryBatchPhase.ActiveGrowth,
        NurseryBatchPhase.HardeningOff,
        activeGrowthChangeNeeded,
    )
    store.changeStatuses(
        batchId,
        NurseryBatchPhase.HardeningOff,
        NurseryBatchPhase.Ready,
        hardeningOffChangeNeeded,
    )

    outPlant?.let { store.withdraw(newWithdrawalModel(batchId, it, WithdrawalPurpose.OutPlant)) }
    transfer?.let { addTransfer(batchId, it) }
    dead?.let { store.withdraw(newWithdrawalModel(batchId, it, WithdrawalPurpose.Dead)) }
    other?.let { store.withdraw(newWithdrawalModel(batchId, it, WithdrawalPurpose.Other)) }

    val result = store.fetchOneById(batchId)

    assertEquals(current.germinating, result.germinatingQuantity, "Current germinating quantity")
    assertEquals(
        current.activeGrowth,
        result.activeGrowthQuantity,
        "Current active growth quantity",
    )
    assertEquals(
        current.hardeningOff,
        result.hardeningOffQuantity,
        "Current hardening off quantity",
    )
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
                hardeningOffQuantity = initial.hardeningOff,
                activeGrowthQuantity = initial.activeGrowth,
                readyQuantity = initial.ready,
                speciesId = speciesId,
            ),
        )
        .id
  }

  private fun addTransfer(batchId: BatchId, quantities: Quantities): ExistingWithdrawalModel {
    return store.withdraw(
        newWithdrawalModel(
            batchId,
            quantities,
            WithdrawalPurpose.NurseryTransfer,
            destinationFacilityId,
        )
    )
  }

  private fun addManualEdits(batchId: BatchId, quantityDeltas: Quantities) {
    val batch = store.fetchOneById(batchId)

    store.updateQuantities(
        batchId,
        batch.version,
        batch.germinatingQuantity + quantityDeltas.germinating,
        batch.activeGrowthQuantity + quantityDeltas.activeGrowth,
        batch.hardeningOffQuantity + quantityDeltas.hardeningOff,
        batch.readyQuantity + quantityDeltas.ready,
        BatchQuantityHistoryType.Observed,
    )
  }

  private fun newWithdrawalModel(
      batchId: BatchId,
      quantities: Quantities,
      purpose: WithdrawalPurpose,
      destinationFacilityId: FacilityId? = null,
      facilityId: FacilityId = this.facilityId,
  ): NewWithdrawalModel {
    return NewWithdrawalModel(
        batchWithdrawals =
            listOf(
                BatchWithdrawalModel(
                    batchId = batchId,
                    germinatingQuantityWithdrawn = quantities.germinating,
                    activeGrowthQuantityWithdrawn = quantities.activeGrowth,
                    hardeningOffQuantityWithdrawn = quantities.hardeningOff,
                    readyQuantityWithdrawn = quantities.ready,
                )
            ),
        destinationFacilityId = destinationFacilityId,
        facilityId = facilityId,
        id = null,
        purpose = purpose,
        withdrawnDate = LocalDate.EPOCH,
    )
  }

  private class Quantities(
      val germinating: Int,
      val activeGrowth: Int,
      val hardeningOff: Int,
      val ready: Int,
  )
}
