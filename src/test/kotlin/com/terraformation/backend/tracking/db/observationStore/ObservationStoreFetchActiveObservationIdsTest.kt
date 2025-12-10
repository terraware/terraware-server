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
  fun `returns observations with active plots in requested strata`() {
    val stratumId1 = insertPlantingZone()
    insertPlantingSubzone()
    val stratum1PlotId1 = insertMonitoringPlot()
    val stratum1PlotId2 = insertMonitoringPlot()
    val stratumId2 = insertPlantingZone()
    insertPlantingSubzone()
    val stratum2PlotId1 = insertMonitoringPlot()
    val stratumId3 = insertPlantingZone()
    insertPlantingSubzone()
    val stratum3PlotId1 = insertMonitoringPlot()

    val observationId1 = insertObservation()
    insertObservationPlot(monitoringPlotId = stratum1PlotId1)
    insertObservationPlot(monitoringPlotId = stratum1PlotId2)
    insertObservationPlot(monitoringPlotId = stratum2PlotId1)
    val observationId2 = insertObservation()
    insertObservationPlot(monitoringPlotId = stratum2PlotId1)
    val observationId3 = insertObservation()
    insertObservationPlot(monitoringPlotId = stratum3PlotId1)

    assertEquals(
        listOf(observationId1),
        store.fetchActiveObservationIds(plantingSiteId, listOf(stratumId1)),
        "Observation with two plots in stratum should be listed once",
    )
    assertEquals(
        listOf(observationId1, observationId2),
        store.fetchActiveObservationIds(plantingSiteId, listOf(stratumId2)),
        "Observations with plots in multiple strata should be returned",
    )
    assertEquals(
        listOf(observationId1, observationId3),
        store.fetchActiveObservationIds(
            plantingSiteId,
            listOf(stratumId1, stratumId3),
        ),
        "Should match observations in all requested strata",
    )
  }

  @Test
  fun `does not return observation if its plots in the requested strata are completed`() {
    insertPlantingZone()
    insertPlantingSubzone()
    val monitoringPlotId1 = insertMonitoringPlot()
    val stratumId2 = insertPlantingZone()
    insertPlantingSubzone()
    val monitoringPlotId2 = insertMonitoringPlot()

    val observationIdWithActivePlotsInBothStrata = insertObservation()
    insertObservationPlot(monitoringPlotId = monitoringPlotId1)
    insertObservationPlot(monitoringPlotId = monitoringPlotId2)

    // Active plot in stratum 1, completed plot in stratum 2
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
        listOf(observationIdWithActivePlotsInBothStrata),
        store.fetchActiveObservationIds(plantingSiteId, listOf(stratumId2)),
    )
  }

  @Test
  fun `does not return ad-hoc observation`() {
    val stratumId = insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true)
    insertObservationPlot()

    assertEquals(
        emptyList<ObservationId>(),
        store.fetchActiveObservationIds(plantingSiteId, listOf(stratumId)),
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
