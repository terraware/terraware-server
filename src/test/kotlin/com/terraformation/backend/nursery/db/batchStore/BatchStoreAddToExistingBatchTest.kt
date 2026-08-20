package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.records.BatchQuantityHistoryRecord
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.nursery.model.ExistingBatchModel
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreAddToExistingBatchTest : BatchStoreTest() {
  private val transferTime = Instant.ofEpochSecond(1000)

  private lateinit var batch: ExistingBatchModel
  private lateinit var batchId: BatchId
  private lateinit var originalAccessionId: AccessionId
  private lateinit var secondAccessionId: AccessionId

  @BeforeEach
  fun setUpTestBatch() {
    insertFacility(type = FacilityType.SeedBank)
    originalAccessionId = insertAccession()
    secondAccessionId = insertAccession()

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
            batchId = batchId,
            germinatingQuantity = 100,
        )

    assertEquals(
        batch.copy(
            germinatingQuantity = 110,
            lossRate = null,
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
                createdTime = Instant.EPOCH,
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
        batchId = batchId,
        germinatingQuantity = 1,
    )

    assertEquals(originalAccessionId, batchesDao.fetchOneById(batchId)?.accessionId)
  }

  @Test
  fun `does not calculate rates for batches with seeds from other accessions`() {
    store.addToExistingBatch(
        accessionId = secondAccessionId,
        batchId = batchId,
        germinatingQuantity = 1,
    )

    store.updateQuantities(
        batchId = batchId,
        version = 2,
        germinating = 0,
        activeGrowth = 12,
        hardeningOff = 3,
        ready = 2,
        historyType = BatchQuantityHistoryType.Computed,
    )

    val batch = batchesDao.fetchOneById(batchId)!!

    assertEquals(null, batch.germinationRate, "Germination rate")
    assertEquals(null, batch.lossRate, "Loss rate")
  }

  @Test
  fun `throws exception if quantities are negative`() {
    assertThrows<IllegalArgumentException> {
      store.addToExistingBatch(
          accessionId = secondAccessionId,
          batchId = batchId,
          germinatingQuantity = -1,
      )
    }
  }
}
