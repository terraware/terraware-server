package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchStageDetailsRow
import com.terraformation.backend.db.nursery.tables.records.BatchQuantityHistoryRecord
import com.terraformation.backend.db.nursery.tables.records.BatchStageDetailsRecord
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.db.nursery.tables.references.BATCH_STAGE_DETAILS
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
    val initialQuantities =
        dslContext
            .selectFrom(BATCH_STAGE_DETAILS)
            .fetchInto(BatchStageDetailsRow::class.java)
            .associateBy { it.stageId }

    val before = batchesDao.fetchOneById(batchId)!!

    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        activeGrowth = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Observed,
        notes = "Some notes",
    )

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            latestObservedTime = updateTime,
            modifiedTime = updateTime,
            totalLost = 0,
            totalLossCandidates = 9,
            version = 2,
        ),
        after,
    )
    assertTableEquals(
        listOf(
            BatchStageDetailsRecord(
                initialQuantities
                    .getValue(BatchStage.Germinating)
                    .copy(quantity = 1, latestObservedQuantity = 1)
            ),
            BatchStageDetailsRecord(
                initialQuantities
                    .getValue(BatchStage.ActiveGrowth)
                    .copy(quantity = 2, latestObservedQuantity = 2)
            ),
            BatchStageDetailsRecord(
                initialQuantities
                    .getValue(BatchStage.HardeningOff)
                    .copy(quantity = 4, latestObservedQuantity = 4)
            ),
            BatchStageDetailsRecord(
                initialQuantities
                    .getValue(BatchStage.Ready)
                    .copy(quantity = 3, latestObservedQuantity = 3)
            ),
        ),
    )

    assertTableEquals(
        BatchQuantityHistoryRecord(
            activeGrowthQuantity = 2,
            batchId = batchId,
            createdBy = user.userId,
            createdTime = clock.instant,
            effectiveTime = clock.instant,
            germinatingQuantity = 1,
            hardeningOffQuantity = 4,
            historyTypeId = BatchQuantityHistoryType.Observed,
            notes = "Some notes",
            readyQuantity = 3,
            version = 2,
        )
    )
  }

  @Test
  fun `does not update latest observed quantities if history type is Computed`() {
    val initialQuantities =
        dslContext
            .selectFrom(BATCH_STAGE_DETAILS)
            .fetchInto(BatchStageDetailsRow::class.java)
            .associateBy { it.stageId }

    val before = batchesDao.fetchOneById(batchId)!!

    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        activeGrowth = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Computed,
    )

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            totalLost = 0,
            totalLossCandidates = 9,
            modifiedTime = updateTime,
            version = 2,
        ),
        after,
    )
    assertTableEquals(
        listOf(
            BatchStageDetailsRecord(
                initialQuantities.getValue(BatchStage.Germinating).copy(quantity = 1)
            ),
            BatchStageDetailsRecord(
                initialQuantities.getValue(BatchStage.ActiveGrowth).copy(quantity = 2)
            ),
            BatchStageDetailsRecord(
                initialQuantities.getValue(BatchStage.HardeningOff).copy(quantity = 4)
            ),
            BatchStageDetailsRecord(
                initialQuantities.getValue(BatchStage.Ready).copy(quantity = 3)
            ),
        ),
    )
  }

  @Test
  fun `inserts quantity history row`() {
    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        activeGrowth = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Computed,
    )

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
                effectiveTime = updateTime,
            ),
        ),
        batchQuantityHistoryDao.findAll().map { it.copy(id = null) },
    )
  }

  @Test
  fun `does nothing if quantities are the same as the current values`() {
    val before = batchesDao.fetchOneById(batchId)!!
    mapOf(
            BatchStage.Germinating to 1,
            BatchStage.ActiveGrowth to 2,
            BatchStage.Ready to 3,
            BatchStage.HardeningOff to 4,
        )
        .forEach { (stage, quantity) ->
          with(BATCH_STAGE_DETAILS) {
            dslContext
                .update(this)
                .set(QUANTITY, quantity)
                .where(BATCH_ID.eq(batchId))
                .and(STAGE_ID.eq(stage))
                .execute()
          }
        }
    val beforeQuantities = dslContext.selectFrom(BATCH_STAGE_DETAILS).fetch()

    store.updateQuantities(
        batchId = batchId,
        version = 1,
        germinating = 1,
        activeGrowth = 2,
        ready = 3,
        hardeningOff = 4,
        historyType = BatchQuantityHistoryType.Observed,
    )

    assertEquals(before, batchesDao.fetchOneById(batchId))
    assertTableEquals(
        beforeQuantities,
        message = "Stage quantities",
    )
    assertTableEmpty(BATCH_QUANTITY_HISTORY)
  }

  @Test
  fun `throws exception if version number does not match current version`() {
    assertThrows<BatchStaleException> {
      store.updateQuantities(
          batchId = batchId,
          version = 0,
          germinating = 1,
          activeGrowth = 1,
          ready = 1,
          hardeningOff = 1,
          historyType = BatchQuantityHistoryType.Observed,
      )
    }
  }
}
