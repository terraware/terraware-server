package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationState
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreFetchUpcomingObservationIdsWithoutPlotsTest : BaseObservationStoreTest() {
  @BeforeEach
  fun insertSite() {
    insertPlantingSite()
    insertStratum()
    insertSubstratum()
  }

  @Test
  fun `returns only non-ad-hoc upcoming observations with no plots`() {
    val withoutPlotsId = insertObservation(state = ObservationState.Upcoming)

    insertObservation(state = ObservationState.Upcoming)
    insertMonitoringPlot()
    insertObservationPlot()

    insertObservation(state = ObservationState.InProgress)
    insertMonitoringPlot()
    insertObservationPlot()

    insertObservation(state = ObservationState.InProgress)

    insertObservation(state = ObservationState.Upcoming, isAdHoc = true)

    insertObservation(state = ObservationState.Abandoned, completedTime = Instant.EPOCH)

    assertEquals(listOf(withoutPlotsId), store.fetchUpcomingObservationIdsWithoutPlots())
  }
}
