package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import org.junit.jupiter.api.Test

class ObservationStoreDeleteObservationTest : BaseObservationStoreTest() {
  @Test
  fun `deletes the observation and its cascaded data`() {
    insertStratum()
    insertSubstratum()
    val plotId = insertMonitoringPlot()
    val speciesId = insertSpecies()
    val observationId = insertObservation()
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = plotId,
        statusId = ObservationPlotStatus.Completed,
    )
    insertRecordedPlant(
        observationId = observationId,
        monitoringPlotId = plotId,
        speciesId = speciesId,
    )

    store.deleteObservation(observationId)

    assertTableEmpty(OBSERVATIONS)
    assertTableEmpty(OBSERVATION_PLOTS)
    assertTableEmpty(RECORDED_PLANTS)
  }

  @Test
  fun `clears plot t0 density only for plots whose t0 was the deleted observation`() {
    insertSpecies()
    insertStratum()
    insertSubstratum()
    val t0PlotId = insertMonitoringPlot()
    val otherPlotId = insertMonitoringPlot()
    val observationId1 = insertObservation()
    val observationId2 = insertObservation()

    insertObservationPlot(observationId = observationId1, monitoringPlotId = t0PlotId)
    insertObservationPlot(observationId = observationId1, monitoringPlotId = otherPlotId)
    insertObservationPlot(observationId = observationId2, monitoringPlotId = t0PlotId)
    insertObservationPlot(observationId = observationId2, monitoringPlotId = otherPlotId)

    insertPlotT0Density(monitoringPlotId = otherPlotId)
    insertPlotT0Observation(monitoringPlotId = otherPlotId, observationId = observationId2)

    val expectedDensities = dslContext.fetch(PLOT_T0_DENSITIES)

    insertPlotT0Density(monitoringPlotId = t0PlotId)
    insertPlotT0Observation(monitoringPlotId = t0PlotId, observationId = observationId1)

    store.deleteObservation(observationId1)

    assertTableEquals(expectedDensities)
  }
}
