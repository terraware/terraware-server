package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.nursery.db.BatchInventoryInsufficientException
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class BatchStoreChangeStatusesTest : BatchStoreTest() {
  private val batchId = BatchId(1)
  private val updateTime = Instant.ofEpochSecond(1000)

  @BeforeEach
  fun setUpTestBatch() {
    insertBatch(
        id = batchId,
        germinatingQuantity = 10,
        notReadyQuantity = 20,
        readyQuantity = 30,
        speciesId = speciesId)

    clock.instant = updateTime
  }

  @Test
  fun `updates quantities and creates history entry`() {
    val before = batchesDao.fetchOneById(batchId)!!

    store.changeStatuses(batchId, 2, 7)

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            germinatingQuantity = 8,
            notReadyQuantity = 15,
            readyQuantity = 37,
            totalLost = 0,
            // moved 2 seeds from germinating to not-ready
            totalLossCandidates = 52,
            modifiedTime = updateTime,
            version = 2),
        after)

    assertEquals(
        listOf(
            BatchQuantityHistoryRow(
                id = BatchQuantityHistoryId(1),
                batchId = batchId,
                historyTypeId = BatchQuantityHistoryType.StatusChanged,
                createdBy = user.userId,
                createdTime = updateTime,
                germinatingQuantity = 8,
                notReadyQuantity = 15,
                readyQuantity = 37,
                version = 2,
            )),
        batchQuantityHistoryDao.findAll())
  }

  @Test
  fun `does not update quantities or create history entry if no statuses are changed`() {
    val before = batchesDao.fetchOneById(batchId)

    store.changeStatuses(batchId, 0, 0)

    assertEquals(before, batchesDao.fetchOneById(batchId))
    assertEquals(emptyList<Any>(), batchQuantityHistoryDao.findAll(), "Quantity history")
  }

  @Test
  fun `throws exception if no permission to update batch`() {
    every { user.canUpdateBatch(any()) } returns false

    assertThrows<AccessDeniedException> { store.changeStatuses(batchId, 1, 1) }
  }

  @Test
  fun `throws exception if not enough seedlings to satisfy request`() {
    assertThrows<BatchInventoryInsufficientException>("Germinating") {
      store.changeStatuses(batchId, 50, 0)
    }
    assertThrows<BatchInventoryInsufficientException>("Not Ready") {
      store.changeStatuses(batchId, 0, 50)
    }
  }
}
