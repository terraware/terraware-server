package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationStoreSurvivalRateInProgressTest : BaseObservationStoreTest() {

  @Nested
  inner class FetchSurvivalRateCalculationInProgress {
    @Test
    fun `throws exception when user lacks permission`() {
      every { user.canReadPlantingSite(any()) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        store.fetchSurvivalRateCalculationInProgress(plantingSiteId)
      }
    }

    @Test
    fun `returns false when no recalculation is in progress`() {
      assertFalse(
          store.fetchSurvivalRateCalculationInProgress(plantingSiteId),
          "No recalculation marker row means no calculation in progress",
      )
    }

    @Test
    fun `returns true when a recalculation marker exists`() {
      insertPlantingSiteSurvivalRateCalculation()

      assertTrue(
          store.fetchSurvivalRateCalculationInProgress(plantingSiteId),
          "Recalculation marker row means a calculation is in progress",
      )
    }
  }
}
