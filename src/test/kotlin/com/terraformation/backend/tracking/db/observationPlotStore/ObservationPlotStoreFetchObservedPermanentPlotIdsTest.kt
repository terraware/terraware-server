package com.terraformation.backend.tracking.db.observationPlotStore

import com.terraformation.backend.db.tracking.ObservationState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservationPlotStoreFetchObservedPermanentPlotIdsTest : BaseObservationPlotStoreTest() {
  @Test
  fun `returns only plots that were observed as permanent`() {
    insertStratum()
    insertSubstratum()
    val permanentPlotId = insertMonitoringPlot(permanentIndex = 1, x = 0, y = 0)
    val temporaryPlotId = insertMonitoringPlot(permanentIndex = 2, x = 1, y = 0)
    insertMonitoringPlot(permanentIndex = 3, x = 2, y = 0)

    insertObservation()
    insertObservationPlot(monitoringPlotId = permanentPlotId, isPermanent = true)
    insertObservationPlot(monitoringPlotId = temporaryPlotId, isPermanent = false)

    assertEquals(
        setOf(permanentPlotId),
        store.fetchObservedPermanentPlotIds(plantingSiteId),
        "Observed permanent plots",
    )
  }

  @Test
  fun `excludes plots of upcoming observations`() {
    insertStratum()
    insertSubstratum()
    val inProgressPlotId = insertMonitoringPlot(permanentIndex = 1, x = 0, y = 0)
    val upcomingPlotId = insertMonitoringPlot(permanentIndex = 2, x = 1, y = 0)

    insertObservation(state = ObservationState.InProgress)
    insertObservationPlot(monitoringPlotId = inProgressPlotId, isPermanent = true)
    insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = upcomingPlotId, isPermanent = true)

    assertEquals(
        setOf(inProgressPlotId),
        store.fetchObservedPermanentPlotIds(plantingSiteId),
        "Observed permanent plots",
    )
  }
}
