package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.tracking.db.BiomassSpeciesNotFoundException
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEventValues
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreUpdateBiomassQuadratSpeciesTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpQuadratSpecies() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    insertSpecies()
    insertObservationBiomassSpecies(speciesId = inserted.speciesId)
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.SouthwestCorner)
    insertObservationBiomassQuadratSpecies(
        abundancePercent = 1,
        position = ObservationPlotPosition.SouthwestCorner,
    )
  }

  @Test
  fun `updates editable fields`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_QUADRAT_SPECIES)

    store.updateBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.SouthwestCorner,
        inserted.speciesId,
        null,
    ) {
      it.copy(abundance = 2)
    }

    val expected = before.copy().apply { abundancePercent = 2 }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassQuadratSpeciesUpdatedEvent(
            changedFrom = BiomassQuadratSpeciesUpdatedEventValues(abundance = 1),
            changedTo = BiomassQuadratSpeciesUpdatedEventValues(abundance = 2),
            biomassSpeciesId = inserted.biomassSpeciesId,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            position = ObservationPlotPosition.SouthwestCorner,
        )
    )
  }

  @Test
  fun `does not publish event or modify database if nothing changed`() {
    val unmodifiedTable = dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES)

    store.updateBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.SouthwestCorner,
        inserted.speciesId,
        null,
    ) {
      it
    }

    assertTableEquals(unmodifiedTable)

    eventPublisher.assertEventNotPublished<BiomassQuadratSpeciesUpdatedEvent>()
  }

  @Test
  fun `throws exception if species is not already present in quadrat`() {
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.NortheastCorner)

    assertThrows<BiomassSpeciesNotFoundException> {
      store.updateBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.NortheastCorner,
          inserted.speciesId,
          null,
      ) {
        it
      }
    }
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    every { user.canUpdateObservation(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          inserted.speciesId,
          null,
      ) {
        it.copy(abundance = 2)
      }
    }
  }
}
