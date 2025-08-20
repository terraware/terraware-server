package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.nursery.db.BatchInventoryInsufficientException
import com.terraformation.backend.nursery.db.BatchPhaseReversalNotAllowedException
import com.terraformation.backend.nursery.model.NurseryBatchPhase
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class BatchStoreChangeStatusesTest : BatchStoreTest() {
  private val updateTime = Instant.ofEpochSecond(1000)

  private lateinit var batchId: BatchId

  @BeforeEach
  fun setUpTestBatch() {
    batchId =
        insertBatch(
            germinatingQuantity = 10,
            activeGrowthQuantity = 20,
            readyQuantity = 30,
            hardeningOffQuantity = 40,
            speciesId = speciesId,
        )

    clock.instant = updateTime
  }

  @Test
  fun `updates quantities and creates history entry`() {
    val before = batchesDao.fetchOneById(batchId)!!

    store.changeStatuses(batchId, NurseryBatchPhase.Germinating, NurseryBatchPhase.ActiveGrowth, 2)
    store.changeStatuses(batchId, NurseryBatchPhase.ActiveGrowth, NurseryBatchPhase.HardeningOff, 7)
    store.changeStatuses(batchId, NurseryBatchPhase.HardeningOff, NurseryBatchPhase.Ready, 5)

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            germinatingQuantity = 8,
            hardeningOffQuantity = 42,
            activeGrowthQuantity = 15,
            readyQuantity = 35,
            totalLost = 0,
            // moved 2 seeds from germinating to active-growth
            totalLossCandidates = 92,
            modifiedTime = updateTime,
            version = 4,
        ),
        after,
    )

    assertEquals(
        listOf(
            BatchQuantityHistoryRow(
                batchId = batchId,
                activeGrowthQuantity = 22,
                historyTypeId = BatchQuantityHistoryType.StatusChanged,
                createdBy = user.userId,
                createdTime = updateTime,
                germinatingQuantity = 8,
                hardeningOffQuantity = 40,
                readyQuantity = 30,
                version = 2,
            ),
            BatchQuantityHistoryRow(
                batchId = batchId,
                activeGrowthQuantity = 15,
                historyTypeId = BatchQuantityHistoryType.StatusChanged,
                createdBy = user.userId,
                createdTime = updateTime,
                germinatingQuantity = 8,
                hardeningOffQuantity = 47,
                readyQuantity = 30,
                version = 3,
            ),
            BatchQuantityHistoryRow(
                batchId = batchId,
                activeGrowthQuantity = 15,
                historyTypeId = BatchQuantityHistoryType.StatusChanged,
                createdBy = user.userId,
                createdTime = updateTime,
                germinatingQuantity = 8,
                hardeningOffQuantity = 42,
                readyQuantity = 35,
                version = 4,
            ),
        ),
        batchQuantityHistoryDao.findAll().map { it.copy(id = null) },
    )
  }

  @Test
  fun `supports moves from any status to any later status`() {
    val before = batchesDao.fetchOneById(batchId)!!

    store.changeStatuses(batchId, NurseryBatchPhase.Germinating, NurseryBatchPhase.HardeningOff, 3)

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            activeGrowthQuantity = 20,
            germinatingQuantity = 7,
            hardeningOffQuantity = 43,
            readyQuantity = 30,
            totalLost = 0,
            totalLossCandidates = 93,
            modifiedTime = updateTime,
            version = 2,
        ),
        after,
    )

    assertEquals(
        listOf(
            BatchQuantityHistoryRow(
                batchId = batchId,
                activeGrowthQuantity = 20,
                historyTypeId = BatchQuantityHistoryType.StatusChanged,
                createdBy = user.userId,
                createdTime = updateTime,
                germinatingQuantity = 7,
                hardeningOffQuantity = 43,
                readyQuantity = 30,
                version = 2,
            )
        ),
        batchQuantityHistoryDao.findAll().map { it.copy(id = null) },
    )
  }

  @Test
  fun `does not update quantities or create history entry if no statuses are changed`() {
    val before = batchesDao.fetchOneById(batchId)

    store.changeStatuses(batchId, NurseryBatchPhase.Germinating, NurseryBatchPhase.ActiveGrowth, 0)

    assertEquals(before, batchesDao.fetchOneById(batchId))
    assertTableEmpty(BATCH_QUANTITY_HISTORY)
  }

  @Test
  fun `does not update quantities or create history entry if phases are the same`() {
    val before = batchesDao.fetchOneById(batchId)

    store.changeStatuses(
        batchId,
        NurseryBatchPhase.ActiveGrowth,
        NurseryBatchPhase.ActiveGrowth,
        10,
    )

    assertEquals(before, batchesDao.fetchOneById(batchId))
    assertTableEmpty(BATCH_QUANTITY_HISTORY)
  }

  @Test
  fun `throws exception if phases move in reverse`() {
    assertThrows<BatchPhaseReversalNotAllowedException> {
      store.changeStatuses(batchId, NurseryBatchPhase.Ready, NurseryBatchPhase.ActiveGrowth, 1)
    }
    assertThrows<BatchPhaseReversalNotAllowedException> {
      store.changeStatuses(batchId, NurseryBatchPhase.Ready, NurseryBatchPhase.HardeningOff, 2)
    }
    assertThrows<BatchPhaseReversalNotAllowedException> {
      store.changeStatuses(
          batchId,
          NurseryBatchPhase.HardeningOff,
          NurseryBatchPhase.Germinating,
          3,
      )
    }
  }

  @Test
  fun `throws exception if no permission to update batch`() {
    every { user.canUpdateBatch(any()) } returns false

    assertThrows<AccessDeniedException> {
      store.changeStatuses(
          batchId,
          NurseryBatchPhase.Germinating,
          NurseryBatchPhase.ActiveGrowth,
          1,
      )
    }
  }

  @Test
  fun `throws exception if not enough seedlings to satisfy request`() {
    assertThrows<BatchInventoryInsufficientException>("Germinating") {
      store.changeStatuses(
          batchId,
          NurseryBatchPhase.Germinating,
          NurseryBatchPhase.ActiveGrowth,
          50,
      )
    }
    assertThrows<BatchInventoryInsufficientException>("Active Growth") {
      store.changeStatuses(batchId, NurseryBatchPhase.ActiveGrowth, NurseryBatchPhase.Ready, 50)
    }
    assertThrows<BatchInventoryInsufficientException>("Hardening Off") {
      store.changeStatuses(batchId, NurseryBatchPhase.HardeningOff, NurseryBatchPhase.Ready, 50)
    }
  }
}
