package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEventValues
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreUpdateBiomassSpeciesTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpRecordedTree() {
    insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    insertSpecies()
    insertObservationBiomassSpecies(speciesId = inserted.speciesId)
  }

  @Test
  fun `updates editable fields`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_SPECIES)

    store.updateBiomassSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        inserted.speciesId,
        null,
    ) {
      it.copy(
          isInvasive = true,
          isThreatened = true,
      )
    }

    val expected =
        before.copy().apply {
          isInvasive = true
          isThreatened = true
        }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassSpeciesUpdatedEvent(
            BiomassSpeciesUpdatedEventValues(isInvasive = false, isThreatened = false),
            BiomassSpeciesUpdatedEventValues(isInvasive = true, isThreatened = true),
            inserted.biomassSpeciesId,
            inserted.monitoringPlotId,
            inserted.observationId,
            inserted.organizationId,
            inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `does not publish event or modify database if nothing changed`() {
    val unmodifiedTable = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)

    store.updateBiomassSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        inserted.speciesId,
        null,
    ) {
      it
    }

    assertTableEquals(unmodifiedTable)

    eventPublisher.assertEventNotPublished<BiomassSpeciesUpdatedEvent>()
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    every { user.canUpdateObservation(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          inserted.speciesId,
          null,
      ) {
        it.copy(isThreatened = true)
      }
    }
  }
}
