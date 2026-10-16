package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class ObservationStoreDeleteObservationTest : BaseObservationStoreTest() {
  @Test
  fun `deletes biomass entries when deleting the observation`() {
    insertStratum()
    insertSubstratum()
    val plotId = insertMonitoringPlot()
    val speciesId = insertSpecies()
    val observationId = insertObservation(observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = plotId,
        statusId = ObservationPlotStatus.Completed,
        completedBy = user.userId,
    )
    insertBiomassRows(observationId, plotId, speciesId)

    store.deleteObservation(observationId)

    assertTableEmpty(OBSERVATION_BIOMASS_DETAILS)
    assertTableEmpty(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
    assertTableEmpty(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
    assertTableEmpty(OBSERVATION_BIOMASS_SPECIES)
    assertTableEmpty(RECORDED_TREES)
  }

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

  private fun insertBiomassRows(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      speciesId: SpeciesId,
  ) {
    insertObservationBiomassDetails(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
    )
    val biomassSpeciesId =
        insertObservationBiomassSpecies(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            speciesId = speciesId,
        )
    insertRecordedTree(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
        biomassSpeciesId = biomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal("1.3"),
        heightM = BigDecimal.TEN,
    )
    insertObservationBiomassQuadratDetails(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
        position = ObservationPlotPosition.SouthwestCorner,
    )
    insertObservationBiomassQuadratSpecies(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
        position = ObservationPlotPosition.SouthwestCorner,
        biomassSpeciesId = biomassSpeciesId,
        abundanceCount = 1,
    )
  }
}
