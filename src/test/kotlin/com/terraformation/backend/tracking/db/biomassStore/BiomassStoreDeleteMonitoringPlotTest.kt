package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Test

class BiomassStoreDeleteMonitoringPlotTest : BaseBiomassStoreTest() {
  @Test
  fun `deletes biomass data when deleting a monitoring plot`() {
    insertStratum()
    insertSubstratum()
    val monitoringPlotId = insertMonitoringPlot()
    val speciesId = insertSpecies()
    val observationId =
        insertObservation(
            completedTime = Instant.EPOCH,
            observationType = ObservationType.BiomassMeasurements,
        )
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
        statusId = ObservationPlotStatus.Completed,
        completedBy = user.userId,
    )
    insertBiomassRows(observationId, monitoringPlotId, speciesId)

    dslContext
        .deleteFrom(MONITORING_PLOTS)
        .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
        .execute()

    assertTableEmpty(OBSERVATION_BIOMASS_DETAILS)
    assertTableEmpty(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
    assertTableEmpty(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
    assertTableEmpty(OBSERVATION_BIOMASS_SPECIES)
    assertTableEmpty(RECORDED_TREES)
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
