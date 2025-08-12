package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreSpeciesSummaryTest : BatchStoreTest() {
  @Test
  fun `does not include germinating quantities in total withdrawn`() {
    val batchId = insertBatch(speciesId = speciesId)
    val withdrawalId = insertNurseryWithdrawal()
    insertBatchWithdrawal(
        batchId = batchId,
        germinatingQuantityWithdrawn = 1,
        activeGrowthQuantityWithdrawn = 2,
        readyQuantityWithdrawn = 4,
        withdrawalId = withdrawalId,
    )

    val summary = store.getSpeciesSummary(speciesId)

    assertEquals(6, summary.totalWithdrawn, "Total withdrawn")
  }

  @Test
  fun `rounds loss rate to nearest integer`() {
    insertBatch(readyQuantity = 197, totalLost = 306, totalLossCandidates = 1000)

    val summary = store.getSpeciesSummary(speciesId)

    assertEquals(31, summary.lossRate, "Should round 30.6% up to 31%")
  }

  @Test
  fun `includes nurseries that have fully-withdrawn batches`() {
    val facilityId2 = insertFacility(name = "Other Nursery", type = FacilityType.Nursery)
    insertBatch(speciesId = speciesId, facilityId = facilityId)
    insertBatch(speciesId = speciesId, facilityId = facilityId, readyQuantity = 1)
    insertBatch(speciesId = speciesId, facilityId = facilityId2)

    val summary = store.getSpeciesSummary(speciesId)

    assertEquals(listOf(facilityId, facilityId2), summary.nurseries.map { it.id })
    assertEquals(listOf("Nursery", "Other Nursery"), summary.nurseries.map { it.name })
  }
}
