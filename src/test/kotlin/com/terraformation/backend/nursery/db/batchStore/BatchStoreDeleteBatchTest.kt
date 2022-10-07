package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
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
            readyQuantity = 3))

    store.delete(batchId)

    assertEquals(emptyList<BatchesRow>(), batchesDao.findAll(), "Batches")
    assertEquals(emptyList<BatchQuantityHistoryRow>(), batchQuantityHistoryDao.findAll(), "History")
  }

  @Test
  fun `only deletes withdrawals that did not reference other batches`() {
    val batchIdToDelete = insertBatch(speciesId = speciesId)
    val remainingBatchId = insertBatch(speciesId = speciesId)
    val singleBatchWithdrawlId = insertWithdrawal()
    val multipleBatchWithdrawalId = insertWithdrawal()

    insertBatchWithdrawal(batchId = batchIdToDelete, withdrawalId = singleBatchWithdrawlId)
    insertBatchWithdrawal(batchId = batchIdToDelete, withdrawalId = multipleBatchWithdrawalId)
    insertBatchWithdrawal(batchId = remainingBatchId, withdrawalId = multipleBatchWithdrawalId)

    val deleteTime = clock.instant().plusSeconds(60)
    every { clock.instant() } returns deleteTime

    val expectedBatchWithdrawals = batchWithdrawalsDao.fetchByBatchId(remainingBatchId)
    val expectedWithdrawals =
        listOf(
            nurseryWithdrawalsDao
                .fetchOneById(multipleBatchWithdrawalId)!!
                .copy(modifiedTime = deleteTime))

    store.delete(batchIdToDelete)

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
    val sourceBatchId = insertBatch(speciesId = speciesId)
    val destinationBatchId = insertBatch(speciesId = speciesId)
    val withdrawalId = insertWithdrawal()

    insertBatchWithdrawal(
        BatchWithdrawalsRow(
            batchId = sourceBatchId,
            destinationBatchId = destinationBatchId,
            withdrawalId = withdrawalId))

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
    val batchId =
        insertBatch(
            speciesId = speciesId, germinatingQuantity = 1, notReadyQuantity = 2, readyQuantity = 3)
    val withdrawalId = insertWithdrawal(purpose = WithdrawalPurpose.Dead)

    // This batch is not deleted
    insertBatch(
        speciesId = speciesId,
        germinatingQuantity = 100,
        notReadyQuantity = 200,
        readyQuantity = 300)

    insertBatchWithdrawal(
        batchId = batchId,
        withdrawalId = withdrawalId,
        germinatingQuantityWithdrawn = 10,
        readyQuantityWithdrawn = 20,
        notReadyQuantityWithdrawn = 30)

    val summaryBeforeDelete =
        SpeciesSummary(
            germinatingQuantity = 101,
            notReadyQuantity = 202,
            readyQuantity = 303,
            lossRate = 9,
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
            notReadyQuantity = 200,
            readyQuantity = 300,
            lossRate = 0,
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
