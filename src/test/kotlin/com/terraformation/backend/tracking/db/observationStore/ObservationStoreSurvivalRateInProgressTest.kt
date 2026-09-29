package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.tables.pojos.ObservationSubstratumResultsRow
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.every
import java.time.Instant
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
    fun `returns false when no results are flagged for recalculation`() {
      insertStratum()
      insertSubstratum()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationSubstratumResult()

      assertFalse(
          store.fetchSurvivalRateCalculationInProgress(plantingSiteId),
          "No flagged results means no calculation in progress",
      )
    }

    @Test
    fun `returns true when any results are flagged for recalculation`() {
      insertStratum()
      insertSubstratum()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationSubstratumResult(ObservationSubstratumResultsRow(needsRecalculation = true))

      assertTrue(
          store.fetchSurvivalRateCalculationInProgress(plantingSiteId),
          "Flagged results mean a calculation is in progress",
      )
    }
  }
}
