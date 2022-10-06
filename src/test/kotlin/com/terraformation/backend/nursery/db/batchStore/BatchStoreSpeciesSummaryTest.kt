package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreSpeciesSummaryTest : BatchStoreTest() {
  @Test
  fun `does not include germinating quantities in total withdrawn`() {
    val batchId = insertBatch(speciesId = speciesId)
    val withdrawalId = insertWithdrawal()
    insertBatchWithdrawal(
        batchId = batchId,
        germinatingQuantityWithdrawn = 1,
        notReadyQuantityWithdrawn = 2,
        readyQuantityWithdrawn = 4,
        withdrawalId = withdrawalId,
    )

    val summary = store.getSpeciesSummary(speciesId)

    assertEquals(6, summary.totalWithdrawn, "Total withdrawn")
  }

  @Test
  fun `does not include germinating quantities in loss rate`() {
    val batchId =
        insertBatch(
            germinatingQuantity = 10,
            notReadyQuantity = 1,
            readyQuantity = 1,
            speciesId = speciesId,
        )
    val withdrawalId = insertWithdrawal(purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId,
        germinatingQuantityWithdrawn = 20,
        notReadyQuantityWithdrawn = 2,
        readyQuantityWithdrawn = 3,
        withdrawalId = withdrawalId,
    )

    val summary = store.getSpeciesSummary(speciesId)

    // 5 dead withdrawals / 7 total past + current seedlings = 71.4%
    assertEquals(71, summary.lossRate, "Loss rate")
  }

  @Test
  fun `rounds loss rate to nearest integer`() {
    val batchId = insertBatch(speciesId = speciesId, readyQuantity = 197)
    val withdrawalId = insertWithdrawal(purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId, withdrawalId = withdrawalId, notReadyQuantityWithdrawn = 3)

    val summary = store.getSpeciesSummary(speciesId)

    assertEquals(2, summary.lossRate, "Should round 1.5% up to 2%")
  }

  @Test
  fun `includes nurseries that have fully-withdrawn batches`() {
    val facilityId2 = FacilityId(2)
    insertFacility(facilityId2, name = "Other Nursery", type = FacilityType.Nursery)
    insertBatch(speciesId = speciesId, facilityId = facilityId)
    insertBatch(speciesId = speciesId, facilityId = facilityId, readyQuantity = 1)
    insertBatch(speciesId = speciesId, facilityId = facilityId2)

    val summary = store.getSpeciesSummary(speciesId)

    assertEquals(listOf(facilityId, facilityId2), summary.nurseries.map { it.id })
    assertEquals(listOf("Nursery", "Other Nursery"), summary.nurseries.map { it.name })
  }
}
