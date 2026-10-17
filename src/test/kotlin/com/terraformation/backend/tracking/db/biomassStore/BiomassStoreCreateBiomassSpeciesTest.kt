package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreCreateBiomassSpeciesTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpObservation() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `creates catalog and Other species with exact records and events`() {
    val catalogSpeciesId = insertSpecies(scientificName = "Catalog species")

    store.createBiomassSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        catalogSpeciesId,
        null,
        "Catalog common",
        true,
        false,
    )
    store.createBiomassSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        null,
        "Other species",
        "Other common",
        false,
        true,
    )

    val catalogSpeciesName =
        dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(catalogSpeciesId))!!
    val records = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    val catalogRecord = records.single { it.speciesId == catalogSpeciesId }
    val otherRecord = records.single { it.scientificName == "Other species" }
    assertEquals(2, records.size)
    assertEquals("Catalog common", catalogRecord.commonName)
    assertEquals(true, catalogRecord.isInvasive)
    assertEquals(false, catalogRecord.isThreatened)
    assertEquals(null, catalogRecord.scientificName)
    assertEquals(null, otherRecord.speciesId)
    assertEquals("Other common", otherRecord.commonName)
    assertEquals(false, otherRecord.isInvasive)
    assertEquals(true, otherRecord.isThreatened)

    eventPublisher.assertEventPublished(
        BiomassSpeciesCreatedEvent(
            biomassSpeciesId = catalogRecord.id!!,
            commonName = "Catalog common",
            isInvasive = true,
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
            biomassSpeciesId = otherRecord.id!!,
            commonName = "Other common",
            isInvasive = false,
            isThreatened = true,
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
  fun `rejects catalog species from another organization`() {
    val foreignOrganizationId = insertOrganization()
    val foreignSpeciesId = insertSpecies(organizationId = foreignOrganizationId)
    val biomassSpeciesBefore = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)

    assertThrows<SpeciesNotFoundException> {
      store.createBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          foreignSpeciesId,
          null,
          null,
          false,
          false,
      )
    }

    assertEquals(biomassSpeciesBefore, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES))
    eventPublisher.assertNoEventsPublished()
  }

  @Test
  fun `rejects duplicate species and invalid identity`() {
    val speciesId: SpeciesId = insertSpecies()
    store.createBiomassSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        speciesId,
        null,
        null,
        false,
        false,
    )

    assertThrows<IllegalArgumentException> {
      store.createBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          speciesId,
          null,
          null,
          false,
          false,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.createBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          null,
          null,
          null,
          false,
          false,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.createBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          speciesId,
          "Both values",
          null,
          false,
          false,
      )
    }
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
  }

  @Test
  fun `rejects users without quantity permission`() {
    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.createBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          insertSpecies(),
          null,
          null,
          false,
          false,
      )
    }
  }
}
