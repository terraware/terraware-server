package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.records.BatchQuantityHistoryRecord
import com.terraformation.backend.db.nursery.tables.records.BatchStageDetailsRecord
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose as SeedbankWithdrawalPurpose
import com.terraformation.backend.nursery.db.BatchNotFoundException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreBackfillTimeInStageTest : BatchStoreTest() {
  @Test
  fun `backfills legacy effective times and only replays eligible histories`() {
    val timeZone = ZoneId.of("America/Los_Angeles")
    val backfillFacilityId = insertFacility(timeZone = timeZone, type = FacilityType.Nursery)
    val firstCreatedTime = LocalDate.of(2023, 1, 10).atTime(12, 0).toInstant(ZoneOffset.UTC)
    val withdrawalCreatedTime = LocalDate.of(2023, 1, 20).atTime(12, 0).toInstant(ZoneOffset.UTC)
    val postMigrationWithdrawalCreatedTime =
        LocalDate.of(2023, 1, 25).atTime(12, 0).toInstant(ZoneOffset.UTC)
    val postMigrationWithdrawalDate = LocalDate.of(2023, 1, 15)
    val accessionAddedTime = LocalDate.of(2023, 1, 30).atTime(12, 0).toInstant(ZoneOffset.UTC)
    val unmatchedAccessionAddedTime =
        LocalDate.of(2023, 2, 10).atTime(12, 0).toInstant(ZoneOffset.UTC)
    val accessionId = insertAccession()
    val batchA =
        insertBatch(
            facilityId = backfillFacilityId,
            germinatingQuantity = 15,
            seedsSownDate = LocalDate.of(2023, 1, 10),
            version = 5,
        )
    val withdrawalId =
        insertNurseryWithdrawal(
            createdTime = withdrawalCreatedTime,
            facilityId = backfillFacilityId,
            purpose = WithdrawalPurpose.Dead,
            withdrawnDate = LocalDate.of(2023, 1, 5),
        )
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 2,
    )
    val postMigrationWithdrawalId =
        insertNurseryWithdrawal(
            createdTime = postMigrationWithdrawalCreatedTime,
            facilityId = backfillFacilityId,
            purpose = WithdrawalPurpose.Dead,
            withdrawnDate = postMigrationWithdrawalDate,
        )
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 1,
    )
    val accessionWithdrawalDate = LocalDate.of(2023, 1, 25)
    insertSeedbankWithdrawal(
        createdTime = accessionAddedTime,
        date = accessionWithdrawalDate,
        purpose = SeedbankWithdrawalPurpose.Nursery,
    )
    insertSeedbankWithdrawal(
        createdTime = accessionAddedTime.plusSeconds(30),
        date = accessionWithdrawalDate.plusDays(1),
        purpose = SeedbankWithdrawalPurpose.Nursery,
    )
    insertBatchQuantityHistory(
        createdTime = firstCreatedTime,
        germinatingQuantity = 10,
        historyType = BatchQuantityHistoryType.Observed,
        version = 1,
    )
    insertBatchQuantityHistory(
        createdTime = withdrawalCreatedTime,
        germinatingQuantity = 8,
        historyType = BatchQuantityHistoryType.Computed,
        version = 2,
        withdrawalId = withdrawalId,
    )
    insertBatchQuantityHistory(
        createdTime = postMigrationWithdrawalCreatedTime,
        effectiveTime = withdrawalCreatedTime,
        germinatingQuantity = 7,
        historyType = BatchQuantityHistoryType.Computed,
        version = 3,
        withdrawalId = postMigrationWithdrawalId,
    )
    insertBatchQuantityHistory(
        accessionId = accessionId,
        createdTime = accessionAddedTime,
        effectiveTime = accessionWithdrawalDate.atStartOfDay(timeZone).toInstant(),
        germinatingQuantity = 13,
        historyType = BatchQuantityHistoryType.Computed,
        version = 4,
    )
    insertBatchQuantityHistory(
        accessionId = accessionId,
        createdTime = unmatchedAccessionAddedTime,
        germinatingQuantity = 15,
        historyType = BatchQuantityHistoryType.Computed,
        version = 5,
    )

    val batchB = insertBatch(facilityId = backfillFacilityId, germinatingQuantity = 2, version = 3)
    val firstBatchBCreatedTime = LocalDate.of(2023, 3, 1).atTime(12, 0).toInstant(ZoneOffset.UTC)
    val secondBatchBCreatedTime = LocalDate.of(2023, 3, 2).atTime(12, 0).toInstant(ZoneOffset.UTC)
    insertBatchQuantityHistory(
        createdTime = firstBatchBCreatedTime,
        germinatingQuantity = 1,
        historyType = BatchQuantityHistoryType.Observed,
        version = 2,
    )
    insertBatchQuantityHistory(
        createdTime = secondBatchBCreatedTime,
        germinatingQuantity = 2,
        historyType = BatchQuantityHistoryType.Observed,
        version = 3,
    )

    store.backfillTimeInStage(batchA)
    store.backfillTimeInStage(batchB)

    fun historyRecord(
        accessionId: AccessionId? = null,
        batchId: BatchId,
        createdTime: Instant,
        effectiveTime: Instant,
        germinatingQuantity: Int,
        historyTypeId: BatchQuantityHistoryType,
        version: Int,
        withdrawalId: WithdrawalId? = null,
    ) =
        BatchQuantityHistoryRecord(
            accessionId = accessionId,
            activeGrowthQuantity = 0,
            batchId = batchId,
            createdBy = user.userId,
            createdTime = createdTime,
            effectiveTime = effectiveTime,
            germinatingQuantity = germinatingQuantity,
            hardeningOffQuantity = 0,
            historyTypeId = historyTypeId,
            readyQuantity = 0,
            version = version,
            withdrawalId = withdrawalId,
        )

    assertTableEquals(
        listOf(
            historyRecord(
                batchId = batchA,
                createdTime = firstCreatedTime,
                effectiveTime = firstCreatedTime,
                germinatingQuantity = 10,
                historyTypeId = BatchQuantityHistoryType.Observed,
                version = 1,
            ),
            historyRecord(
                batchId = batchA,
                createdTime = withdrawalCreatedTime,
                effectiveTime = firstCreatedTime,
                germinatingQuantity = 8,
                historyTypeId = BatchQuantityHistoryType.Computed,
                version = 2,
                withdrawalId = withdrawalId,
            ),
            historyRecord(
                batchId = batchA,
                createdTime = postMigrationWithdrawalCreatedTime,
                effectiveTime = postMigrationWithdrawalDate.atStartOfDay(timeZone).toInstant(),
                germinatingQuantity = 7,
                historyTypeId = BatchQuantityHistoryType.Computed,
                version = 3,
                withdrawalId = postMigrationWithdrawalId,
            ),
            historyRecord(
                accessionId = accessionId,
                batchId = batchA,
                createdTime = accessionAddedTime,
                effectiveTime = accessionWithdrawalDate.atStartOfDay(timeZone).toInstant(),
                germinatingQuantity = 13,
                historyTypeId = BatchQuantityHistoryType.Computed,
                version = 4,
            ),
            historyRecord(
                accessionId = accessionId,
                batchId = batchA,
                createdTime = unmatchedAccessionAddedTime,
                effectiveTime = unmatchedAccessionAddedTime,
                germinatingQuantity = 15,
                historyTypeId = BatchQuantityHistoryType.Computed,
                version = 5,
            ),
            historyRecord(
                batchId = batchB,
                createdTime = firstBatchBCreatedTime,
                effectiveTime = firstBatchBCreatedTime,
                germinatingQuantity = 1,
                historyTypeId = BatchQuantityHistoryType.Observed,
                version = 2,
            ),
            historyRecord(
                batchId = batchB,
                createdTime = secondBatchBCreatedTime,
                effectiveTime = secondBatchBCreatedTime,
                germinatingQuantity = 2,
                historyTypeId = BatchQuantityHistoryType.Observed,
                version = 3,
            ),
        ),
        message = "Backfilled effective times",
    )

    // The corrected history through Feb 10 yields 315.1667 germinating days.
    fun calculatedStage(batchId: BatchId, stage: BatchStage, quantity: Int, days: Double) =
        BatchStageDetailsRecord(
            batchId = batchId,
            countedQuantity = quantity,
            countedSeedlingDays = days,
            finishedQuantity = 0.0,
            finishedSeedlingDays = 0.0,
            latestObservedQuantity = quantity,
            openSeedlingDays = days,
            quantity = quantity,
            stageId = stage,
            timeInStageAsOf = unmatchedAccessionAddedTime,
            timeInStageCleared = false,
        )

    fun uncalculatedStage(batchId: BatchId, stage: BatchStage, quantity: Int) =
        BatchStageDetailsRecord(
            batchId = batchId,
            countedQuantity = null,
            countedSeedlingDays = null,
            latestObservedQuantity = quantity,
            openSeedlingDays = null,
            quantity = quantity,
            stageId = stage,
            timeInStageAsOf = null,
            timeInStageCleared = null,
        )

    assertTableEquals(
        listOf(
            calculatedStage(batchA, BatchStage.Germinating, 15, 315.1666666666667),
            calculatedStage(batchA, BatchStage.ActiveGrowth, 0, 0.0),
            calculatedStage(batchA, BatchStage.HardeningOff, 0, 0.0),
            calculatedStage(batchA, BatchStage.Ready, 0, 0.0),
            uncalculatedStage(batchB, BatchStage.Germinating, 2),
            uncalculatedStage(batchB, BatchStage.ActiveGrowth, 0),
            uncalculatedStage(batchB, BatchStage.HardeningOff, 0),
            uncalculatedStage(batchB, BatchStage.Ready, 0),
        ),
        message = "Backfilled time-in-stage stats for complete and ineligible histories",
    )
  }

  @Test
  fun `throws exception if batch does not exist`() {
    assertThrows<BatchNotFoundException> { store.backfillTimeInStage(BatchId(-1)) }
  }
}
