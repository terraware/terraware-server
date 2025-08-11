package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.records.BatchesRecord
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.nursery.event.BatchDeletionStartedEvent
import com.terraformation.backend.nursery.model.SpeciesSummary
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
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
  fun `deletes batch and history if batch has no withdrawals`() {
    val batchId = insertBatch(speciesId = speciesId)
    batchQuantityHistoryDao.insert(
        BatchQuantityHistoryRow(
            batchId = batchId,
            activeGrowthQuantity = 2,
            historyTypeId = BatchQuantityHistoryType.Observed,
            createdBy = user.userId,
            createdTime = clock.instant(),
            germinatingQuantity = 1,
            readyQuantity = 3,
            hardeningOffQuantity = 4,
            version = 1))

    store.delete(batchId)

    assertTableEmpty(BATCHES)
    assertTableEmpty(BATCH_QUANTITY_HISTORY)

    eventPublisher.assertEventPublished(BatchDeletionStartedEvent(batchId))
  }

  @Test
  fun `sets remaining quantities to zero if batch has withdrawals`() {
    val batchId =
        insertBatch(
            germinatingQuantity = 1,
            activeGrowthQuantity = 2,
            hardeningOffQuantity = 3,
            readyQuantity = 4,
            speciesId = speciesId)
    insertNurseryWithdrawal()
    insertBatchWithdrawal()

    store.delete(batchId)

    assertTableEquals(
        BatchesRecord(
            id = batchId,
            version = 2,
            organizationId = organizationId,
            facilityId = facilityId,
            speciesId = speciesId,
            batchNumber = "1",
            addedDate = LocalDate.EPOCH,
            germinatingQuantity = 0,
            activeGrowthQuantity = 0,
            hardeningOffQuantity = 0,
            readyQuantity = 0,
            latestObservedGerminatingQuantity = 0,
            latestObservedActiveGrowthQuantity = 0,
            latestObservedHardeningOffQuantity = 0,
            latestObservedReadyQuantity = 0,
            latestObservedTime = Instant.EPOCH,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            modifiedBy = user.userId,
            modifiedTime = Instant.EPOCH))

    eventPublisher.assertEventNotPublished<BatchDeletionStartedEvent>()
  }

  @Test
  fun `removes association with destination batch of transfer withdrawal`() {
    val destinationBatchId = insertBatch()

    insertBatch()
    insertNurseryWithdrawal()
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
        activeGrowthQuantity = 200,
        readyQuantity = 300,
        hardeningOffQuantity = 400,
    )

    val batchId =
        insertBatch(
            germinatingQuantity = 1,
            germinationRate = 100,
            totalGerminationCandidates = 10,
            totalGerminated = 10,
            lossRate = 0,
            totalLossCandidates = 100,
            totalLost = 0,
            activeGrowthQuantity = 2,
            readyQuantity = 3,
            hardeningOffQuantity = 4,
        )
    insertNurseryWithdrawal(purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 10,
        readyQuantityWithdrawn = 20,
        activeGrowthQuantityWithdrawn = 30,
        hardeningOffQuantityWithdrawn = 40,
    )

    val summaryBeforeDelete =
        SpeciesSummary(
            germinatingQuantity = 101,
            germinationRate = 58,
            hardeningOffQuantity = 404,
            activeGrowthQuantity = 202,
            readyQuantity = 303,
            lossRate = 13,
            nurseries = listOf(FacilitiesRow(id = facilityId, name = "Nursery")),
            speciesId = speciesId,
            totalDead = 90,
            totalQuantity = 909,
            totalWithdrawn = 90)
    assertEquals(
        summaryBeforeDelete, store.getSpeciesSummary(speciesId), "Summary before deleting batch")

    store.delete(batchId)

    // Total dead and total withdrawn are unaffected because they are part of the withdrawal history
    assertEquals(
        SpeciesSummary(
            germinatingQuantity = 100,
            germinationRate = 50,
            hardeningOffQuantity = 400,
            activeGrowthQuantity = 200,
            readyQuantity = 300,
            lossRate = 25,
            nurseries = summaryBeforeDelete.nurseries,
            speciesId = speciesId,
            totalDead = 90,
            totalQuantity = 900,
            totalWithdrawn = 90),
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
