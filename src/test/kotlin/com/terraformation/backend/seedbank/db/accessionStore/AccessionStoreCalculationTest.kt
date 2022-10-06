package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreCalculationTest : AccessionStoreTest() {
  @Test
  fun `update recalculates estimated seed count and weight`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val total = SeedQuantityModel(BigDecimal.TEN, SeedQuantityUnits.Pounds)
    store.update(
        initial.copy(
            processingMethod = ProcessingMethod.Weight,
            subsetCount = 1,
            subsetWeightQuantity = SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Ounces),
            total = total))
    val fetched = store.fetchOneById(initial.id!!)

    assertEquals(160, fetched.estimatedSeedCount, "Estimated seed count is added")
    assertEquals(total, fetched.estimatedWeight, "Estimated weight is added")

    store.update(fetched.copy(total = null))

    val fetchedAfterClear = store.fetchOneById(initial.id!!)

    assertNull(fetchedAfterClear.estimatedSeedCount, "Estimated seed count is removed")
    assertNull(fetchedAfterClear.estimatedWeight, "Estimated weight is removed")
  }

  @Test
  fun `update recalculates seeds remaining when seed count is filled in`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    val fetched = store.fetchOneById(initial.id!!)

    assertEquals(seeds<SeedQuantityModel>(10), fetched.remaining)
  }

  @Test
  fun `update requires subset weight to use weight units`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Weight,
              total = grams(10),
              subsetWeightQuantity = seeds(5)))
    }
  }
}
