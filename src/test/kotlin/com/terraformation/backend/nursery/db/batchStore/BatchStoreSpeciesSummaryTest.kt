package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.nursery.model.SpeciesStageTimeModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class BatchStoreSpeciesSummaryTest : BatchStoreTest() {
  @Test
  fun `weights finished stage time by seedlings and excludes cleared uncalculated and other species rows`() {
    listOf(1.0 to 1.0, 10.0 to 20.0, 100.0 to 300.0).forEach { (quantity, days) ->
      insertBatch(insertStageDetails = false)
      insertBatchStageDetails(
          finishedQuantity = quantity,
          finishedSeedlingDays = days,
          timeInStageCleared = false,
      )
    }
    insertBatch(insertStageDetails = false)
    insertBatchStageDetails(
        finishedQuantity = 1000.0,
        finishedSeedlingDays = 90000.0,
        timeInStageCleared = true,
    )
    insertBatch(insertStageDetails = false)
    insertBatchStageDetails()
    insertBatch(insertStageDetails = false)
    insertBatchStageDetails(finishedQuantity = 0.0, finishedSeedlingDays = 0.0)
    insertBatch(insertStageDetails = false, speciesId = insertSpecies())
    insertBatchStageDetails(finishedQuantity = 1000.0, finishedSeedlingDays = 90000.0)

    assertEquals(
        mapOf(
            BatchStage.Germinating to
                SpeciesStageTimeModel.of(
                    averageDays = 321.0 / 111,
                    batchCount = 3,
                    typicalRangeBatchCount = 2,
                ),
            BatchStage.ActiveGrowth to SpeciesStageTimeModel.of(),
            BatchStage.HardeningOff to SpeciesStageTimeModel.of(),
            BatchStage.Ready to SpeciesStageTimeModel.of(),
        ),
        store.getSpeciesSummary(speciesId).averageTimeInStage,
        "Weighted species stage times and empty stages",
    )
  }

  @CsvSource(
      "2,10,,2,,",
      "3,10,2.3333333333333335,3,,",
      "5,9.5,6.2,0,,",
      "4,10,3.75,4,,",
      "5,10,6.2,5,2.0,8.0",
      "6,10,10.5,6,2.5,14.0",
  )
  @ParameterizedTest(name = "{0} batches with {1} finished seedlings")
  fun `applies batch and seedling thresholds to averages and interpolated quartiles`(
      batchCount: Int,
      quantity: Double,
      averageDays: Double?,
      rangeBatchCount: Int,
      minDays: Double?,
      maxDays: Double?,
  ) {
    listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0).take(batchCount).forEach { days ->
      insertBatch(insertStageDetails = false)
      insertBatchStageDetails(
          finishedQuantity = quantity,
          finishedSeedlingDays = days * quantity,
          stage = BatchStage.Ready,
      )
    }

    val actual = store.getSpeciesSummary(speciesId).averageTimeInStage.getValue(BatchStage.Ready)
    val expected =
        SpeciesStageTimeModel.of(
            averageDays = averageDays,
            batchCount = batchCount,
            typicalMaxDays = maxDays,
            typicalMinDays = minDays,
            typicalRangeBatchCount = rangeBatchCount,
        )
    assertEquals(expected, actual, "Batch and seedling thresholds and interpolated quartiles")
  }

  @Test
  fun `does not include germinating quantities in total withdrawn`() {
    val batchId = insertBatch(speciesId = speciesId)
    val withdrawalId = insertNurseryWithdrawal()
    insertBatchWithdrawal(
        batchId = batchId,
        germinatingQuantityWithdrawn = 1,
        activeGrowthQuantityWithdrawn = 2,
        hardeningOffQuantityWithdrawn = 3,
        readyQuantityWithdrawn = 4,
        withdrawalId = withdrawalId,
    )

    val summary = store.getSpeciesSummary(speciesId)

    assertEquals(9, summary.totalWithdrawn, "Total withdrawn")
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
