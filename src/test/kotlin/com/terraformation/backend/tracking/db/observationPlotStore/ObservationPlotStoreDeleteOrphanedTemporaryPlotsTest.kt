package com.terraformation.backend.tracking.db.observationPlotStore

import com.terraformation.backend.db.tracking.ObservationState
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservationPlotStoreDeleteOrphanedTemporaryPlotsTest : BaseObservationPlotStoreTest() {
  @Test
  fun `deletes only unreferenced temporary plots in the list`() {
    every { user.canUpdatePlantingSite(any()) } returns true

    insertStratum()
    insertSubstratum()
    val orphanedPlotId = insertMonitoringPlot()
    val referencedPlotId = insertMonitoringPlot()
    val permanentPlotId = insertMonitoringPlot(permanentIndex = 1)
    val adHocPlotId = insertMonitoringPlot(isAdHoc = true)
    val unlistedOrphanedPlotId = insertMonitoringPlot()

    insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = referencedPlotId)

    store.deleteOrphanedTemporaryPlots(
        listOf(orphanedPlotId, referencedPlotId, permanentPlotId, adHocPlotId)
    )

    assertEquals(
        setOf(referencedPlotId, permanentPlotId, adHocPlotId, unlistedOrphanedPlotId),
        monitoringPlotsDao.findAll().map { it.id }.toSet(),
    )
  }
}
