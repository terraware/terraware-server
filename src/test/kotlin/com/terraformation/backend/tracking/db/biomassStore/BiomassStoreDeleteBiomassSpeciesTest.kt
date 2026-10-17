package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.db.BiomassSpeciesNotFoundException
import com.terraformation.backend.tracking.event.BiomassSpeciesDeletedEvent
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreDeleteBiomassSpeciesTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpObservation() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    insertSpecies(scientificName = "Existing species")
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `deletes an unreferenced species and publishes its exact event`() {
    val biomassSpeciesId =
        insertObservationBiomassSpecies(
            speciesId = inserted.speciesId,
            commonName = "Existing common",
            isInvasive = true,
            isThreatened = true,
        )
    val otherBiomassSpeciesId = insertObservationBiomassSpecies(scientificName = "Other species")
    val speciesName =
        dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(inserted.speciesId))!!

    store.deleteBiomassSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        inserted.speciesId,
        null,
    )

    val remaining = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    assertEquals(listOf(otherBiomassSpeciesId), remaining.map { it.id })
    eventPublisher.assertEventPublished(
        BiomassSpeciesDeletedEvent(
            biomassSpeciesId = biomassSpeciesId,
            commonName = "Existing common",
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            scientificName = null,
            speciesId = inserted.speciesId,
            speciesName = speciesName,
        )
    )
  }

  @Test
  fun `rejects deletion while a tree or quadrat entry references the species`() {
    val treeBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)
    insertRecordedTree(
        biomassSpeciesId = treeBiomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal("1.3"),
    )
    val quadratSpeciesId = insertSpecies()
    val quadratBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = quadratSpeciesId)
    insertObservationBiomassQuadratDetails()
    insertObservationBiomassQuadratSpecies(biomassSpeciesId = quadratBiomassSpeciesId)

    val treeReferenceException =
        assertThrows<IllegalArgumentException> {
          store.deleteBiomassSpecies(
              inserted.observationId,
              inserted.monitoringPlotId,
              inserted.speciesId,
              null,
          )
        }
    val quadratReferenceException =
        assertThrows<IllegalArgumentException> {
          store.deleteBiomassSpecies(
              inserted.observationId,
              inserted.monitoringPlotId,
              quadratSpeciesId,
              null,
          )
        }

    assertEquals(
        "Biomass species is still referenced by trees or quadrat entries",
        treeReferenceException.message,
    )
    assertEquals(treeReferenceException.message, quadratReferenceException.message)
    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(1, dslContext.fetch(RECORDED_TREES).size)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `rejects missing species and invalid identity`() {
    assertThrows<BiomassSpeciesNotFoundException> {
      store.deleteBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          inserted.speciesId,
          null,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.deleteBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          null,
          null,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.deleteBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          inserted.speciesId,
          "Both values",
      )
    }
  }

  @Test
  fun `rejects users without quantity permission`() {
    insertObservationBiomassSpecies(speciesId = inserted.speciesId)
    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.deleteBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          inserted.speciesId,
          null,
      )
    }
  }
}
