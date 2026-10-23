package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassAdditionalSpeciesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.tracking.db.ObservationPlotNotFoundException
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
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot()
    insertObservationBiomassDetails()
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `creates known and Other species with exact events`() {
    val knownSpeciesId = insertSpecies(scientificName = "Known species")

    createQuadratSpecies(knownSpeciesId, abundance = 0)
    val knownBiomassSpeciesId =
        dslContext.fetchValue(
            OBSERVATION_BIOMASS_SPECIES.ID,
            OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(knownSpeciesId),
        )!!
    eventPublisher.assertExactEventsPublished(
        listOf(
            BiomassSpeciesCreatedEvent(
                biomassSpeciesId = knownBiomassSpeciesId,
                commonName = null,
                isInvasive = false,
                isThreatened = false,
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                scientificName = null,
                speciesId = knownSpeciesId,
                speciesName = "Known species",
            ),
            BiomassQuadratCreatedEvent(
                description = null,
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.NortheastCorner,
            ),
            BiomassQuadratSpeciesCreatedEvent(
                abundance = 0,
                biomassSpeciesId = knownBiomassSpeciesId,
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.NortheastCorner,
                speciesId = knownSpeciesId,
                speciesName = "Known species",
            ),
        )
    )

    val otherSpeciesName = "Other species"
    createQuadratSpecies(
        speciesId = null,
        position = ObservationPlotPosition.NorthwestCorner,
        scientificName = otherSpeciesName,
        abundance = 12,
    )

    val biomassSpecies = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    assertEquals(
        knownSpeciesId,
        biomassSpecies.single { it.speciesId != null }.speciesId,
        "Known species should get a biomass species row",
    )
    assertEquals(
        otherSpeciesName,
        biomassSpecies.single { it.speciesId == null }.scientificName,
        "Other species should get a biomass species row",
    )
    assertEquals(
        2,
        dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size,
        "Number of quadrat species entries",
    )
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.scientificName == otherSpeciesName
    }
  }

  @Test
  fun `removes species from additional species of only the quadrat's plot`() {
    val speciesId = insertSpecies()
    val monitoringPlotId = inserted.monitoringPlotId
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    insertObservationBiomassAdditionalSpecies()

    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot(monitoringPlotId = otherMonitoringPlotId)
    insertObservationBiomassDetails(monitoringPlotId = otherMonitoringPlotId)
    insertObservationBiomassAdditionalSpecies(
        monitoringPlotId = otherMonitoringPlotId,
        biomassSpeciesId = biomassSpeciesId,
    )

    store.createBiomassQuadratSpecies(
        inserted.observationId,
        monitoringPlotId,
        ObservationPlotPosition.NortheastCorner,
        speciesId,
        null,
        1,
    )

    assertTableEquals(
        ObservationBiomassAdditionalSpeciesRecord(
            biomassSpeciesId = biomassSpeciesId,
            monitoringPlotId = otherMonitoringPlotId,
            observationId = inserted.observationId,
        ),
        "Additional species after creating quadrat species",
    )
  }

  @Test
  fun `rejects invalid identity, abundance, foreign species, and permission`() {
    assertThrows<IllegalArgumentException> { createQuadratSpecies(null) }

    val foreignOrganizationId = insertOrganization()
    val foreignSpeciesId = insertSpecies(organizationId = foreignOrganizationId)
    assertThrows<IllegalArgumentException> {
      createQuadratSpecies(foreignSpeciesId, abundance = -1)
    }
    assertThrows<SpeciesNotFoundException> {
      createQuadratSpecies(foreignSpeciesId, abundance = 3)
    }

    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false
    assertThrows<AccessDeniedException> { createQuadratSpecies(foreignSpeciesId) }

    val monitoringObservationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.Monitoring)
    insertObservationPlot(observationId = monitoringObservationId)
    assertThrows<ObservationPlotNotFoundException> {
      store.createBiomassQuadratSpecies(
          monitoringObservationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.NortheastCorner,
          null,
          "Other",
          1,
      )
    }

    assertTableEmpty(OBSERVATION_BIOMASS_SPECIES)
    assertTableEmpty(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
    assertTableEmpty(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
    eventPublisher.assertNoEventsPublished()
  }

  private fun createQuadratSpecies(
      speciesId: SpeciesId?,
      position: ObservationPlotPosition = ObservationPlotPosition.NortheastCorner,
      scientificName: String? = null,
      abundance: Int = 1,
  ) =
      store.createBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          position,
          speciesId,
          scientificName,
          abundance,
      )
}
