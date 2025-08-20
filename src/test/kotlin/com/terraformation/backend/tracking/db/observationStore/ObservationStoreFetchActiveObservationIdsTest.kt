package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationStoreFetchActiveObservationIdsTest : BaseObservationStoreTest() {
  @Test
  fun `returns observations with active plots in requested zones`() {
    val plantingZoneId1 = insertPlantingZone()
    insertPlantingSubzone()
    val zone1PlotId1 = insertMonitoringPlot()
    val zone1PlotId2 = insertMonitoringPlot()
    val plantingZoneId2 = insertPlantingZone()
    insertPlantingSubzone()
    val zone2PlotId1 = insertMonitoringPlot()
    val plantingZoneId3 = insertPlantingZone()
    insertPlantingSubzone()
    val zone3PlotId1 = insertMonitoringPlot()

    val observationId1 = insertObservation()
    insertObservationPlot(monitoringPlotId = zone1PlotId1)
    insertObservationPlot(monitoringPlotId = zone1PlotId2)
    insertObservationPlot(monitoringPlotId = zone2PlotId1)
    val observationId2 = insertObservation()
    insertObservationPlot(monitoringPlotId = zone2PlotId1)
    val observationId3 = insertObservation()
    insertObservationPlot(monitoringPlotId = zone3PlotId1)

    assertEquals(
        listOf(observationId1),
        store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId1)),
        "Observation with two plots in zone should be listed once",
    )
    assertEquals(
        listOf(observationId1, observationId2),
        store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId2)),
        "Observations with plots in multiple zones should be returned",
    )
    assertEquals(
        listOf(observationId1, observationId3),
        store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId1, plantingZoneId3)),
        "Should match observations in all requested zones",
    )
  }

  @Test
  fun `does not return observation if its plots in the requested zones are completed`() {
    insertPlantingZone()
    insertPlantingSubzone()
    val monitoringPlotId1 = insertMonitoringPlot()
    val plantingZoneId2 = insertPlantingZone()
    insertPlantingSubzone()
    val monitoringPlotId2 = insertMonitoringPlot()

    val observationIdWithActivePlotsInBothZones = insertObservation()
    insertObservationPlot(monitoringPlotId = monitoringPlotId1)
    insertObservationPlot(monitoringPlotId = monitoringPlotId2)

    // Active plot in zone 1, completed plot in zone 2
    insertObservation()
    insertObservationPlot(monitoringPlotId = monitoringPlotId1)
    insertObservationPlot(monitoringPlotId = monitoringPlotId2, completedBy = user.userId)

    // Abandoned observation
    insertObservation(completedTime = Instant.EPOCH, state = ObservationState.Abandoned)
    insertObservationPlot(
        monitoringPlotId = monitoringPlotId2,
        statusId = ObservationPlotStatus.NotObserved,
    )

    assertEquals(
        listOf(observationIdWithActivePlotsInBothZones),
        store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId2)),
    )
  }

  @Test
  fun `does not return ad-hoc observation`() {
    val plantingZoneId = insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true)
    insertObservationPlot()

    assertEquals(
        emptyList<ObservationId>(),
        store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId)),
    )
  }

  @Test
  fun `throws exception if no permission to read planting site`() {
    every { user.canReadPlantingSite(any()) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchActiveObservationIds(plantingSiteId, emptyList())
    }
  }
}
