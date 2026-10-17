package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
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
    eventPublisher.assertEventPublished(
        BiomassQuadratCreatedEvent(
            description = null,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            position = ObservationPlotPosition.SouthwestCorner,
        )
    )
    eventPublisher.assertEventPublished(
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
        )
    )
    eventPublisher.assertEventNotPublished<BiomassSpeciesCreatedEvent>()
  }

  @Test
  fun `creates catalog and Other species with creation events`() {
    val catalogSpeciesId = insertSpecies()
    val catalogSpeciesName =
        dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(catalogSpeciesId))!!

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
        "Other species",
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
            OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME.eq("Other species"),
        )!!

    eventPublisher.assertEventPublished(
        BiomassSpeciesCreatedEvent(
            biomassSpeciesId = catalogBiomassSpeciesId,
            commonName = null,
            isInvasive = false,
            isThreatened = false,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            scientificName = null,
            speciesId = catalogSpeciesId,
            speciesName = catalogSpeciesName,
        )
    )
    eventPublisher.assertEventPublished(
        BiomassSpeciesCreatedEvent(
            biomassSpeciesId = otherBiomassSpeciesId,
            commonName = null,
            isInvasive = false,
            isThreatened = false,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            scientificName = "Other species",
            speciesId = null,
            speciesName = "Other species",
        )
    )
  }

  @Test
  fun `rejects duplicate species, invalid identity, and out-of-range abundance`() {
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.SouthwestCorner)
    insertObservationBiomassQuadratSpecies(position = ObservationPlotPosition.SouthwestCorner)

    assertThrows<IllegalArgumentException> {
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          inserted.speciesId,
          null,
          1,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          null,
          null,
          1,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          inserted.speciesId,
          "Both values",
          1,
      )
    }
    listOf(-1, 26).forEach { abundance ->
      assertThrows<IllegalArgumentException> {
        store.createBiomassQuadratSpecies(
            inserted.observationId,
            inserted.monitoringPlotId,
            ObservationPlotPosition.SouthwestCorner,
            inserted.speciesId,
            null,
            abundance,
        )
      }
    }
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
