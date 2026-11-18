package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Polygon

class ObservationResultsStoreV2UpcomingTest : ObservationScenarioTest() {
  @Test
  fun `returns plots assigned to an upcoming observation under their current strata and substrata`() {
    insertStratum(name = "Stratum A")
    insertSubstratum(name = "Substratum A1")
    val permanentPlotId = insertMonitoringPlot(permanentIndex = 1, plotNumber = 11)
    val temporaryPlotId = insertMonitoringPlot(plotNumber = 12)

    // A second map version after the plots were created, so the "current history" fallback has to
    // pick the latest site history rather than any matching one.
    insertPlantingSiteHistory()
    insertStratumHistory()
    insertSubstratumHistory()
    insertMonitoringPlotHistory(monitoringPlotId = permanentPlotId)
    insertMonitoringPlotHistory(monitoringPlotId = temporaryPlotId)

    insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = permanentPlotId, isPermanent = true)
    insertObservationPlot(monitoringPlotId = temporaryPlotId, isPermanent = false)

    fun expectedPlot(monitoringPlotId: MonitoringPlotId, plotNumber: Long, isPermanent: Boolean) =
        ObservationMonitoringPlotResultsModel(
            biomassDetails = null,
            boundary = monitoringPlotsDao.fetchOneById(monitoringPlotId)!!.boundary as Polygon,
            claimedByName = null,
            claimedByUserId = null,
            completedTime = null,
            conditions = emptySet(),
            coordinates = emptyList(),
            currentStratumId = inserted.stratumId,
            currentSubstratumId = inserted.substratumId,
            elevationMeters = null,
            isAdHoc = false,
            isPermanent = isPermanent,
            media = emptyList(),
            monitoringPlotId = monitoringPlotId,
            monitoringPlotNumber = plotNumber,
            notes = null,
            overlappedByPlotIds = emptySet(),
            overlapsWithPlotIds = emptySet(),
            pending = false,
            plantingDensity = null,
            plants = emptyList(),
            sizeMeters = MONITORING_PLOT_SIZE_INT,
            species = emptyList(),
            status = ObservationPlotStatus.Unclaimed,
            stratumId = inserted.stratumId,
            stratumName = "Stratum A",
            substratumId = inserted.substratumId,
            substratumName = "Substratum A1",
            survivalRate = null,
            totalPlants = null,
            totalSpecies = null,
        )

    val expectedPlots =
        listOf(
            expectedPlot(permanentPlotId, 11L, true),
            expectedPlot(temporaryPlotId, 12L, false),
        )

    val results = resultsStoreV2.fetchOneById(inserted.observationId, ObservationResultsDepth.Plant)

    assertNull(results.plantingSiteHistoryId, "Planting site history ID")
    assertFalse(results.anyPlotsCompleted, "Any plots completed")

    val stratum = results.strata.single()
    assertEquals(inserted.stratumId, stratum.stratumId, "Stratum ID")
    assertEquals("Stratum A", stratum.name, "Stratum name")

    val substratum = stratum.substrata.single()
    assertEquals(inserted.substratumId, substratum.substratumId, "Substratum ID")
    assertEquals("Substratum A1", substratum.name, "Substratum name")

    assertEquals(expectedPlots, substratum.monitoringPlots, "Plots from observation results")

    assertEquals(
        expectedPlots,
        resultsStoreV2
            .fetchByPlantingSiteId(plantingSiteId, ObservationResultsDepth.Plant)
            .single { it.observationId == inserted.observationId }
            .strata
            .single()
            .substrata
            .single()
            .monitoringPlots,
        "Plots from planting site results",
    )
  }
}
