package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.nursery.db.BatchStaleException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreUpdateQuantitiesTest : BatchStoreTest() {
  private val updateTime = Instant.ofEpochSecond(1000)

  private lateinit var batchId: BatchId

  @BeforeEach
  fun setUpTestBatch() {
    batchId = insertBatch(readyQuantity = 1, speciesId = speciesId)

    clock.instant = updateTime
  }

  @Test
  fun `updates latest observed quantities if history type is Observed`() {
    val before = batchesDao.fetchOneById(batchId)!!

    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        notReady = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Observed)

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            germinatingQuantity = 1,
            activeGrowthQuantity = 2,
            readyQuantity = 3,
            hardeningOffQuantity = 4,
            latestObservedGerminatingQuantity = 1,
            latestObservedActiveGrowthQuantity = 2,
            latestObservedReadyQuantity = 3,
            latestObservedHardeningOffQuantity = 4,
            latestObservedTime = updateTime,
            modifiedTime = updateTime,
            totalLost = 0,
            totalLossCandidates = 9,
            version = 2),
        after)
  }

  @Test
  fun `does not update latest observed quantities if history type is Computed`() {
    val before = batchesDao.fetchOneById(batchId)!!

    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        notReady = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Computed)

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            germinatingQuantity = 1,
            activeGrowthQuantity = 2,
            readyQuantity = 3,
            hardeningOffQuantity = 4,
            totalLost = 0,
            totalLossCandidates = 9,
            modifiedTime = updateTime,
            version = 2),
        after)
  }

  @Test
  fun `inserts quantity history row`() {
    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        notReady = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Computed)

    assertEquals(
        listOf(
            BatchQuantityHistoryRow(
                batchId = batchId,
                historyTypeId = BatchQuantityHistoryType.Computed,
                createdBy = user.userId,
                createdTime = updateTime,
                germinatingQuantity = 1,
                activeGrowthQuantity = 2,
                readyQuantity = 3,
                hardeningOffQuantity = 4,
                version = 2,
            ),
        ),
        batchQuantityHistoryDao.findAll().map { it.copy(id = null) })
  }

  @Test
  fun `does nothing if quantities are the same as the current values`() {
    val before =
        batchesDao
            .fetchOneById(batchId)!!
            .copy(
                germinatingQuantity = 1,
                activeGrowthQuantity = 2,
                readyQuantity = 3,
                hardeningOffQuantity = 4)
    batchesDao.update(before)

    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        notReady = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Observed)

    assertEquals(before, batchesDao.fetchOneById(batchId))
    assertTableEmpty(BATCH_QUANTITY_HISTORY)
  }

  @Test
  fun `throws exception if version number does not match current version`() {
    assertThrows<BatchStaleException> {
      store.updateQuantities(
          batchId = batchId,
          version = 0,
          germinating = 1,
          notReady = 1,
          ready = 1,
          hardeningOff = 1,
          historyType = BatchQuantityHistoryType.Observed)
    }
  }
}
