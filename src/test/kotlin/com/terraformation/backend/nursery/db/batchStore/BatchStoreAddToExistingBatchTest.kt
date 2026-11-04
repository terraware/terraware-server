package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.tables.records.BatchQuantityHistoryRecord
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.StageTimeStats
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreAddToExistingBatchTest : BatchStoreTest() {
  private val addedDate = LocalDate.of(2025, 8, 30)
  private val batchCreatedTime = Instant.parse("2025-08-29T00:00:00Z")
  private val transferTime = Instant.parse("2025-09-01T00:16:40Z")

  private lateinit var batch: ExistingBatchModel
  private lateinit var batchId: BatchId
  private lateinit var originalAccessionId: AccessionId
  private lateinit var secondAccessionId: AccessionId

  @BeforeEach
  fun setUpTestBatch() {
    insertFacility(type = FacilityType.SeedBank)
    originalAccessionId = insertAccession(number = "1")
    secondAccessionId = insertAccession(number = "2")

    clock.instant = batchCreatedTime
    batch =
        store.create(
            makeNewBatchModel().copy(accessionId = originalAccessionId, germinatingQuantity = 10)
        )

    batchId = batch.id

    clock.instant = transferTime
  }

  @Test
  fun `adds quantities to existing batch`() {
    val updatedBatch =
        store.addToExistingBatch(
            accessionId = secondAccessionId,
            addedDate = addedDate,
            batchId = batchId,
            germinatingQuantity = 100,
        )

    val asOfTime = Instant.parse("2025-08-30T00:00:00Z")
    fun stageStats(stage: BatchStage, quantity: Int, days: Double) =
        StageTimeStats(
            asOfTime = asOfTime,
            cleared = false,
            countedDays = days,
            countedQuantity = quantity,
            inStage = quantity,
            openDays = days,
            stage = stage,
        )

    assertEquals(
        batch.copy(
            accessionNumbers = mapOf(originalAccessionId to "1", secondAccessionId to "2"),
            germinatingQuantity = 110,
            lossRate = 0,
            timeInStage =
                mapOf(
                    BatchStage.Germinating to stageStats(BatchStage.Germinating, 110, 10.0),
                    BatchStage.ActiveGrowth to stageStats(BatchStage.ActiveGrowth, 1, 1.0),
                    BatchStage.HardeningOff to stageStats(BatchStage.HardeningOff, 3, 3.0),
                    BatchStage.Ready to stageStats(BatchStage.Ready, 2, 2.0),
                ),
            version = 2,
        ),
        updatedBatch,
        "Batch after adding seeds",
    )
  }

  @Test
  fun `records accession the seeds came from in quantity history`() {
    store.addToExistingBatch(
        accessionId = secondAccessionId,
        addedDate = addedDate,
        batchId = batchId,
        germinatingQuantity = 100,
    )

    assertTableEquals(
        listOf(
            BatchQuantityHistoryRecord(
                accessionId = originalAccessionId,
                activeGrowthQuantity = 1,
                batchId = batchId,
                createdBy = user.userId,
                createdTime = batchCreatedTime,
                effectiveTime = batchCreatedTime,
                germinatingQuantity = 10,
                hardeningOffQuantity = 3,
                historyTypeId = BatchQuantityHistoryType.Observed,
                readyQuantity = 2,
                version = 1,
            ),
            BatchQuantityHistoryRecord(
                accessionId = secondAccessionId,
                activeGrowthQuantity = 1,
                batchId = batchId,
                createdBy = user.userId,
                createdTime = transferTime,
                effectiveTime = Instant.parse("2025-08-30T00:00:00Z"),
                germinatingQuantity = 110,
                hardeningOffQuantity = 3,
                historyTypeId = BatchQuantityHistoryType.Computed,
                readyQuantity = 2,
                version = 2,
            ),
        )
    )
  }

  @Test
  fun `does not modify accession ID of batch`() {
    store.addToExistingBatch(
        accessionId = secondAccessionId,
        addedDate = addedDate,
        batchId = batchId,
        germinatingQuantity = 1,
    )

    assertEquals(originalAccessionId, batchesDao.fetchOneById(batchId)?.accessionId)
  }

  @Test
  fun `throws exception if quantities are negative`() {
    assertThrows<IllegalArgumentException> {
      store.addToExistingBatch(
          accessionId = secondAccessionId,
          addedDate = addedDate,
          batchId = batchId,
          germinatingQuantity = -1,
      )
    }
  }
}
