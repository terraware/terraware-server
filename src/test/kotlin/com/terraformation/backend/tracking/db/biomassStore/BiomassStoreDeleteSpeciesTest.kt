package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import java.math.BigDecimal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

class BiomassStoreDeleteSpeciesTest : BaseBiomassStoreTest() {
  @Test
  fun `rejects deleting biomass species referenced by a recorded tree`() {
    val biomassSpeciesId = insertBiomassSpecies()
    insertRecordedTree(
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal("1.3"),
        heightM = BigDecimal.TEN,
    )

    assertThrows<DataIntegrityViolationException> {
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()
    }
  }

  @Test
  fun `rejects deleting biomass species referenced by a quadrat species entry`() {
    val biomassSpeciesId = insertBiomassSpecies()
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.SouthwestCorner)
    insertObservationBiomassQuadratSpecies(
        position = ObservationPlotPosition.SouthwestCorner,
        abundanceCount = 1,
    )

    assertThrows<DataIntegrityViolationException> {
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()
    }
  }

  private fun insertBiomassSpecies(): BiomassSpeciesId {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    insertSpecies()

    return insertObservationBiomassSpecies(speciesId = inserted.speciesId)
  }
}
