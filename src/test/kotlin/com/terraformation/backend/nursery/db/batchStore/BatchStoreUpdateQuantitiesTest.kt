package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.nursery.db.BatchStaleException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreUpdateQuantitiesTest : BatchStoreTest() {
  private val batchId = BatchId(1)
  private val updateTime = Instant.ofEpochSecond(1000)

  @BeforeEach
  fun setUpTestBatch() {
    insertBatch(id = batchId, readyQuantity = 1, speciesId = speciesId)

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
        historyType = BatchQuantityHistoryType.Observed)

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            germinatingQuantity = 1,
            notReadyQuantity = 2,
            readyQuantity = 3,
            latestObservedGerminatingQuantity = 1,
            latestObservedNotReadyQuantity = 2,
            latestObservedReadyQuantity = 3,
            latestObservedTime = updateTime,
            modifiedTime = updateTime,
            totalLost = 0,
            totalLossCandidates = 5,
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
        historyType = BatchQuantityHistoryType.Computed)

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            germinatingQuantity = 1,
            notReadyQuantity = 2,
            readyQuantity = 3,
            totalLost = 0,
            totalLossCandidates = 5,
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
        historyType = BatchQuantityHistoryType.Computed)

    assertEquals(
        listOf(
            BatchQuantityHistoryRow(
                id = BatchQuantityHistoryId(1),
                batchId = batchId,
                historyTypeId = BatchQuantityHistoryType.Computed,
                createdBy = user.userId,
                createdTime = updateTime,
                germinatingQuantity = 1,
                notReadyQuantity = 2,
                readyQuantity = 3,
                version = 2,
            ),
        ),
        batchQuantityHistoryDao.findAll())
  }

  @Test
  fun `does nothing if quantities are the same as the current values`() {
    val before =
        batchesDao
            .fetchOneById(batchId)!!
            .copy(germinatingQuantity = 1, notReadyQuantity = 2, readyQuantity = 3)
    batchesDao.update(before)

    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        notReady = 2,
        ready = 3,
        historyType = BatchQuantityHistoryType.Observed)

    assertEquals(before, batchesDao.fetchOneById(batchId))
    assertEquals(emptyList<Any>(), batchQuantityHistoryDao.findAll(), "Quantity history")
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
          historyType = BatchQuantityHistoryType.Observed)
    }
  }
}
