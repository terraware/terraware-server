package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassAdditionalSpeciesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
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
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `deletes an unreferenced species`() {
    insertObservationPlot()
    insertObservationBiomassDetails()
    val speciesId = insertSpecies(scientificName = "Existing species")
    val biomassSpeciesId =
        insertObservationBiomassSpecies(
            speciesId = speciesId,
            commonName = "Existing common",
        )
    val otherBiomassSpeciesId = insertObservationBiomassSpecies(scientificName = "Other species")
    insertObservationBiomassAdditionalSpecies(biomassSpeciesId = biomassSpeciesId)
    insertObservationBiomassAdditionalSpecies(biomassSpeciesId = otherBiomassSpeciesId)

    deleteBiomassSpecies(speciesId)

    assertEquals(
        otherBiomassSpeciesId,
        dslContext.fetchSingle(OBSERVATION_BIOMASS_SPECIES).id,
        "Remaining biomass species after deleting target species",
    )
    assertTableEquals(
        ObservationBiomassAdditionalSpeciesRecord(
            biomassSpeciesId = otherBiomassSpeciesId,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
        ),
        "Remaining additional species after deleting target species",
    )
    eventPublisher.assertExactEventsPublished(
        listOf(
            BiomassSpeciesDeletedEvent(
                biomassSpeciesId = biomassSpeciesId,
                commonName = "Existing common",
                isImplicit = false,
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                scientificName = null,
                speciesId = speciesId,
                speciesName = "Existing species",
            )
        )
    )
  }

  @Test
  fun `keeps species referenced by trees or quadrat entries`() {
    insertObservationPlot()
    insertObservationBiomassDetails()
    val treeSpeciesId = insertSpecies()
    val treeBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = treeSpeciesId)
    insertRecordedTree(
        biomassSpeciesId = treeBiomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal("1.3"),
    )
    val quadratSpeciesId = insertSpecies()
    val quadratBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = quadratSpeciesId)
    insertObservationBiomassQuadratDetails()
    insertObservationBiomassQuadratSpecies(biomassSpeciesId = quadratBiomassSpeciesId)

    assertThrows<IllegalArgumentException> { deleteBiomassSpecies(treeSpeciesId) }
    assertThrows<IllegalArgumentException> { deleteBiomassSpecies(quadratSpeciesId) }
    val biomassSpeciesIds = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).map { it.id }.toSet()
    assertEquals(
        setOf(treeBiomassSpeciesId, quadratBiomassSpeciesId),
        biomassSpeciesIds,
        "Biomass species after rejecting deletion of referenced species",
    )
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `rejects missing species and invalid identity`() {
    assertThrows<BiomassSpeciesNotFoundException> { deleteBiomassSpecies(insertSpecies()) }
    assertThrows<IllegalArgumentException> { deleteBiomassSpecies(null) }
  }

  @Test
  fun `rejects users without quantity permission`() {
    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> { deleteBiomassSpecies(null) }
  }

  private fun deleteBiomassSpecies(speciesId: SpeciesId?) =
      store.deleteBiomassSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          speciesId,
          null,
      )
}
