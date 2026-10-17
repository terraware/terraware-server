package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.tracking.event.BiomassQuadratCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreCreateBiomassQuadratSpeciesTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpObservation() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    insertSpecies(scientificName = "Existing species")
    insertObservationBiomassSpecies(speciesId = inserted.speciesId)
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `adds an existing biomass species and creates missing quadrat details`() {
    store.createBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.SouthwestCorner,
        inserted.speciesId,
        null,
        25,
    )

    assertEquals(25, dslContext.fetchSingle(OBSERVATION_BIOMASS_QUADRAT_SPECIES).abundanceCount)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_DETAILS).size)
    eventPublisher.assertExactEventsPublished(
        listOf(
            BiomassQuadratCreatedEvent(
                description = null,
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.SouthwestCorner,
            ),
            BiomassQuadratSpeciesCreatedEvent(
                abundance = 25,
                biomassSpeciesId = inserted.biomassSpeciesId,
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.SouthwestCorner,
                speciesId = inserted.speciesId,
                speciesName = "Existing species",
            ),
        )
    )
  }

  @Test
  fun `creates catalog and Other species with creation events`() {
    val catalogSpeciesId = insertSpecies()
    val otherSpeciesName = "Other species"

    store.createBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.NortheastCorner,
        catalogSpeciesId,
        null,
        0,
    )
    store.createBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.NorthwestCorner,
        null,
        otherSpeciesName,
        12,
    )

    val catalogBiomassSpeciesId =
        dslContext.fetchValue(
            OBSERVATION_BIOMASS_SPECIES.ID,
            OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(catalogSpeciesId),
        )!!
    val otherBiomassSpeciesId =
        dslContext.fetchValue(
            OBSERVATION_BIOMASS_SPECIES.ID,
            OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME.eq(otherSpeciesName),
        )!!

    assertEquals(
        setOf(catalogBiomassSpeciesId, otherBiomassSpeciesId),
        dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).map { it.biomassSpeciesId }.toSet(),
    )
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.speciesId == catalogSpeciesId
    }
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.scientificName == otherSpeciesName
    }
  }

  @Test
  fun `rejects invalid species, identity, and abundance`() {
    val position = ObservationPlotPosition.SouthwestCorner
    assertThrows<IllegalArgumentException> {
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          position,
          null,
          null,
          1,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          position,
          inserted.speciesId,
          null,
          -1,
      )
    }

    val foreignOrganizationId = insertOrganization()
    val foreignSpeciesId = insertSpecies(organizationId = foreignOrganizationId)
    assertThrows<SpeciesNotFoundException> {
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          position,
          foreignSpeciesId,
          null,
          3,
      )
    }

    assertEquals(
        setOf(inserted.biomassSpeciesId),
        dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).map { it.id }.toSet(),
    )
    assertEquals(0, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_DETAILS).size)
    assertEquals(0, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
    eventPublisher.assertNoEventsPublished()
  }

  @Test
  fun `rejects users without quantity permission`() {
    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          inserted.speciesId,
          null,
          1,
      )
    }
  }
}
