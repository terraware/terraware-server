package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
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
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `creates catalog and Other species with exact records and events`() {
    insertObservationPlot()
    insertObservationBiomassDetails()
    val catalogSpeciesId = insertSpecies(scientificName = "Catalog species")

    createBiomassSpecies(catalogSpeciesId, commonName = "Catalog common", isInvasive = true)
    createBiomassSpecies(
        null,
        scientificName = "Other species",
        commonName = "Other common",
        isThreatened = true,
    )

    val records = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    val catalogRecord = records.single { it.speciesId == catalogSpeciesId }
    val otherRecord = records.single { it.scientificName == "Other species" }
    assertEquals(
        setOf(
            listOf(catalogSpeciesId, null, "Catalog common", true, false),
            listOf(null, "Other species", "Other common", false, true),
        ),
        records
            .map {
              listOf(
                  it.speciesId,
                  it.scientificName,
                  it.commonName,
                  it.isInvasive,
                  it.isThreatened,
              )
            }
            .toSet(),
    )

    assertThrows<IllegalArgumentException> { createBiomassSpecies(catalogSpeciesId) }
    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    eventPublisher.assertExactEventsPublished(
        listOf(
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
                speciesName = "Catalog species",
            ),
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
            ),
        )
    )
  }

  @Test
  fun `rejects foreign species and missing identity`() {
    insertObservationPlot()
    val foreignSpeciesId = insertSpecies(organizationId = insertOrganization())

    assertThrows<SpeciesNotFoundException> { createBiomassSpecies(foreignSpeciesId) }
    assertEquals(0, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    eventPublisher.assertNoEventsPublished()

    assertThrows<IllegalArgumentException> { createBiomassSpecies(null) }
  }

  @Test
  fun `rejects users without quantity permission`() {
    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> { createBiomassSpecies(null) }
  }

  private fun createBiomassSpecies(
      speciesId: SpeciesId?,
      scientificName: String? = null,
      commonName: String? = null,
      isInvasive: Boolean = false,
      isThreatened: Boolean = false,
  ) =
      store.createBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          speciesId,
          scientificName,
          commonName,
          isInvasive,
          isThreatened,
      )
}
