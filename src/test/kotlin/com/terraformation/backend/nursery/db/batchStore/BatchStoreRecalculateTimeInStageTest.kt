package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.tables.records.BatchStageDetailsRecord
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreRecalculateTimeInStageTest : BatchStoreTest() {
  @Test
  fun `recalculates finished stats when effective times are already populated`() {
    val createdTime = Instant.parse("2026-01-01T00:00:00Z")
    val stageChangeTime = Instant.parse("2026-01-11T00:00:00Z")
    clock.instant = createdTime
    insertBatch(
        germinatingQuantity = 20,
        readyQuantity = 80,
        insertStageDetails = false,
        version = 2,
    )
    insertBatchStageDetails(
        countedQuantity = 100,
        countedSeedlingDays = 1000.0,
        latestObservedQuantity = 100,
        openSeedlingDays = 200.0,
        quantity = 20,
        stage = BatchStage.Germinating,
        timeInStageAsOf = stageChangeTime,
        timeInStageCleared = false,
    )
    insertBatchStageDetails(
        countedQuantity = 0,
        countedSeedlingDays = 0.0,
        latestObservedQuantity = 0,
        openSeedlingDays = 0.0,
        quantity = 0,
        stage = BatchStage.ActiveGrowth,
        timeInStageAsOf = stageChangeTime,
        timeInStageCleared = false,
    )
    insertBatchStageDetails(
        countedQuantity = 0,
        countedSeedlingDays = 0.0,
        latestObservedQuantity = 0,
        openSeedlingDays = 0.0,
        quantity = 0,
        stage = BatchStage.HardeningOff,
        timeInStageAsOf = stageChangeTime,
        timeInStageCleared = false,
    )
    insertBatchStageDetails(
        countedQuantity = 80,
        countedSeedlingDays = 0.0,
        latestObservedQuantity = 0,
        openSeedlingDays = 0.0,
        quantity = 80,
        stage = BatchStage.Ready,
        timeInStageAsOf = stageChangeTime,
        timeInStageCleared = false,
    )
    insertBatchQuantityHistory(
        createdTime = createdTime,
        effectiveTime = createdTime,
        germinatingQuantity = 100,
    )
    insertBatchQuantityHistory(
        createdTime = stageChangeTime,
        effectiveTime = stageChangeTime,
        germinatingQuantity = 20,
        historyType = BatchQuantityHistoryType.StatusChanged,
        readyQuantity = 80,
        version = 2,
    )

    store.recalculateTimeInStage(inserted.batchId)

    assertTableEquals(
        listOf(
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                countedQuantity = 100,
                countedSeedlingDays = 1000.0,
                finishedQuantity = 80.0,
                finishedSeedlingDays = 800.0,
                latestObservedQuantity = 100,
                openSeedlingDays = 200.0,
                quantity = 20,
                stageId = BatchStage.Germinating,
                timeInStageAsOf = stageChangeTime,
                timeInStageCleared = false,
            ),
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                countedQuantity = 0,
                countedSeedlingDays = 0.0,
                finishedQuantity = 0.0,
                finishedSeedlingDays = 0.0,
                latestObservedQuantity = 0,
                openSeedlingDays = 0.0,
                quantity = 0,
                stageId = BatchStage.ActiveGrowth,
                timeInStageAsOf = stageChangeTime,
                timeInStageCleared = false,
            ),
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                countedQuantity = 0,
                countedSeedlingDays = 0.0,
                finishedQuantity = 0.0,
                finishedSeedlingDays = 0.0,
                latestObservedQuantity = 0,
                openSeedlingDays = 0.0,
                quantity = 0,
                stageId = BatchStage.HardeningOff,
                timeInStageAsOf = stageChangeTime,
                timeInStageCleared = false,
            ),
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                countedQuantity = 80,
                countedSeedlingDays = 0.0,
                finishedQuantity = 0.0,
                finishedSeedlingDays = 0.0,
                latestObservedQuantity = 0,
                openSeedlingDays = 0.0,
                quantity = 80,
                stageId = BatchStage.Ready,
                timeInStageAsOf = stageChangeTime,
                timeInStageCleared = false,
            ),
        ),
        message = "Recalculated completed seedlings without backfilling effective times",
    )
  }

  @Test
  fun `does not populate stats for non-replayable history`() {
    val createdTime = Instant.parse("2026-01-01T00:00:00Z")
    clock.instant = createdTime
    insertBatch(germinatingQuantity = 10, version = 2)
    insertBatchQuantityHistory(
        effectiveTime = createdTime,
        germinatingQuantity = 10,
        version = 2,
    )

    store.recalculateTimeInStage(inserted.batchId)

    assertTableEquals(
        listOf(
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                latestObservedQuantity = 10,
                quantity = 10,
                stageId = BatchStage.Germinating,
            ),
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                latestObservedQuantity = 0,
                quantity = 0,
                stageId = BatchStage.ActiveGrowth,
            ),
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                latestObservedQuantity = 0,
                quantity = 0,
                stageId = BatchStage.HardeningOff,
            ),
            BatchStageDetailsRecord(
                batchId = inserted.batchId,
                latestObservedQuantity = 0,
                quantity = 0,
                stageId = BatchStage.Ready,
            ),
        ),
        message = "Incomplete history leaves stage statistics null",
    )
  }

  @Test
  fun `selects only batches with calculated stage time and missing finished quantity`() {
    val needsRecalculation = insertBatch(insertStageDetails = false)
    insertBatchStageDetails(countedQuantity = 10)
    insertBatchStageDetails(countedQuantity = 0, stage = BatchStage.Ready)

    insertBatch(insertStageDetails = false)
    insertBatchStageDetails(
        countedQuantity = 10,
        finishedQuantity = 5.0,
        finishedSeedlingDays = 50.0,
    )

    insertBatch(insertStageDetails = false)
    insertBatchStageDetails()

    insertBatch(insertStageDetails = false)
    insertBatchStageDetails(finishedQuantity = 0.0)

    assertEquals(
        listOf(needsRecalculation),
        store.fetchBatchIdsNeedingFinishedTimeInStage(),
        "Batches needing finished time in stage, without duplicates",
    )
  }
}
