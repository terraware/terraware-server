package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.nursery.event.BatchDeletionStartedEvent
import com.terraformation.backend.nursery.event.WithdrawalDeletionStartedEvent
import com.terraformation.backend.nursery.model.SpeciesSummary
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class BatchStoreDeleteBatchTest : BatchStoreTest() {
  @BeforeEach
  fun grantDeletePermission() {
    every { user.canDeleteBatch(any()) } returns true
  }

  @Test
  fun `deletes history`() {
    val batchId = insertBatch(speciesId = speciesId)
    batchQuantityHistoryDao.insert(
        BatchQuantityHistoryRow(
            batchId = batchId,
            historyTypeId = BatchQuantityHistoryType.Observed,
            createdBy = user.userId,
            createdTime = clock.instant(),
            germinatingQuantity = 1,
            notReadyQuantity = 2,
            readyQuantity = 3,
            version = 1))

    store.delete(batchId)

    assertEquals(emptyList<BatchesRow>(), batchesDao.findAll(), "Batches")
    assertEquals(emptyList<BatchQuantityHistoryRow>(), batchQuantityHistoryDao.findAll(), "History")
  }

  @Test
  fun `only deletes withdrawals that did not reference other batches`() {
    val singleBatchWithdrawlId = insertWithdrawal()
    val batchIdToDelete = insertBatch()
    insertBatchWithdrawal()

    val multipleBatchWithdrawalId = insertWithdrawal()
    insertBatchWithdrawal()

    val remainingBatchId = insertBatch()
    insertBatchWithdrawal()

    val deleteTime = clock.instant().plusSeconds(60)
    clock.instant = deleteTime

    val expectedBatchWithdrawals = batchWithdrawalsDao.fetchByBatchId(remainingBatchId)
    val expectedWithdrawals =
        listOf(
            nurseryWithdrawalsDao
                .fetchOneById(multipleBatchWithdrawalId)!!
                .copy(modifiedTime = deleteTime))

    store.delete(batchIdToDelete)

    eventPublisher.assertExactEventsPublished(
        listOf(
            BatchDeletionStartedEvent(batchIdToDelete),
            WithdrawalDeletionStartedEvent(singleBatchWithdrawlId)))

    assertEquals(
        expectedWithdrawals,
        nurseryWithdrawalsDao.findAll(),
        "Withdrawal from multiple batches should not be deleted")
    assertEquals(
        expectedBatchWithdrawals,
        batchWithdrawalsDao.findAll(),
        "Batch withdrawals from other batches should not be deleted")
  }

  @Test
  fun `removes association with destination batch of transfer withdrawal`() {
    val destinationBatchId = insertBatch()

    insertBatch()
    insertWithdrawal()
    insertBatchWithdrawal(destinationBatchId = destinationBatchId)

    val expectedBatchWithdrawals =
        batchWithdrawalsDao.findAll().map { it.copy(destinationBatchId = null) }

    store.delete(destinationBatchId)

    assertEquals(expectedBatchWithdrawals, batchWithdrawalsDao.findAll())
  }

  // In the current implementation, the summary is not actually "updated" (it is a query across the
  // underlying data that aggregates the results, so there's nothing to update) but that's an
  // implementation detail, not part of the API contract.
  @Test
  fun `species summary is updated to reflect deleted batch`() {
    // This batch is not deleted
    insertBatch(
        germinatingQuantity = 100,
        germinationRate = 50,
        totalGerminationCandidates = 50,
        totalGerminated = 25,
        lossRate = 25,
        totalLossCandidates = 100,
        totalLost = 25,
        notReadyQuantity = 200,
        readyQuantity = 300)

    val batchId =
        insertBatch(
            germinatingQuantity = 1,
            germinationRate = 100,
            totalGerminationCandidates = 10,
            totalGerminated = 10,
            lossRate = 0,
            totalLossCandidates = 100,
            totalLost = 0,
            notReadyQuantity = 2,
            readyQuantity = 3)
    insertWithdrawal(purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 10,
        readyQuantityWithdrawn = 20,
        notReadyQuantityWithdrawn = 30)

    val summaryBeforeDelete =
        SpeciesSummary(
            germinatingQuantity = 101,
            germinationRate = 58,
            notReadyQuantity = 202,
            readyQuantity = 303,
            lossRate = 13,
            nurseries = listOf(FacilitiesRow(id = facilityId, name = "Nursery")),
            speciesId = speciesId,
            totalDead = 50,
            totalQuantity = 505,
            totalWithdrawn = 50)
    assertEquals(
        summaryBeforeDelete, store.getSpeciesSummary(speciesId), "Summary before deleting batch")

    store.delete(batchId)

    assertEquals(
        SpeciesSummary(
            germinatingQuantity = 100,
            germinationRate = 50,
            notReadyQuantity = 200,
            readyQuantity = 300,
            lossRate = 25,
            nurseries = summaryBeforeDelete.nurseries,
            speciesId = speciesId,
            totalDead = 0,
            totalQuantity = 500,
            totalWithdrawn = 0),
        store.getSpeciesSummary(speciesId),
        "Summary after deleting batch")
  }

  @Test
  fun `throws exception if user has no permission to delete batch`() {
    every { user.canDeleteBatch(any()) } returns false

    val batchId = insertBatch(speciesId = speciesId)

    assertThrows<AccessDeniedException> { store.delete(batchId) }
  }
}
