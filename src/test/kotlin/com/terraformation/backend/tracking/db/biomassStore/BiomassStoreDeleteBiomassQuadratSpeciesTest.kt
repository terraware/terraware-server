package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.tracking.db.BiomassSpeciesNotFoundException
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesDeletedEvent
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreDeleteBiomassQuadratSpeciesTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpObservation() {
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `deletes selected species and cleans up only unreferenced biomass species`() {
    insertObservationPlot()
    insertObservationBiomassDetails()
    val targetSpeciesId = insertSpecies(scientificName = "Existing species")
    val targetBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = targetSpeciesId)
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.SouthwestCorner)
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.SoutheastCorner)
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.NortheastCorner)
    insertObservationBiomassQuadratSpecies()
    insertObservationBiomassQuadratSpecies(position = ObservationPlotPosition.SoutheastCorner)
    val otherBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = insertSpecies())
    insertObservationBiomassQuadratSpecies()
    val orphanSpeciesId = insertSpecies()
    val orphanBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = orphanSpeciesId)
    insertObservationBiomassQuadratSpecies(position = ObservationPlotPosition.NortheastCorner)
    insertRecordedTree(
        biomassSpeciesId = targetBiomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal("1.3"),
    )

    deleteQuadratSpecies(targetSpeciesId)

    assertEquals(
        setOf(
            ObservationPlotPosition.SoutheastCorner to targetBiomassSpeciesId,
            ObservationPlotPosition.SouthwestCorner to otherBiomassSpeciesId,
            ObservationPlotPosition.NortheastCorner to orphanBiomassSpeciesId,
        ),
        dslContext
            .fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
            .map { it.positionId to it.biomassSpeciesId }
            .toSet(),
    )
    eventPublisher.assertExactEventsPublished(
        listOf(
            BiomassQuadratSpeciesDeletedEvent(
                biomassSpeciesId = targetBiomassSpeciesId,
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.SouthwestCorner,
                speciesId = targetSpeciesId,
                speciesName = "Existing species",
            )
        )
    )
    deleteQuadratSpecies(targetSpeciesId, ObservationPlotPosition.SoutheastCorner)
    assertEquals(
        setOf(targetBiomassSpeciesId, otherBiomassSpeciesId, orphanBiomassSpeciesId),
        dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).map { it.id }.toSet(),
    )
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()

    deleteQuadratSpecies(orphanSpeciesId, ObservationPlotPosition.NortheastCorner)

    assertEquals(
        setOf(targetBiomassSpeciesId, otherBiomassSpeciesId),
        dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).map { it.id }.toSet(),
    )
    assertEquals(
        setOf(ObservationPlotPosition.SouthwestCorner to otherBiomassSpeciesId),
        dslContext
            .fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
            .map { it.positionId to it.biomassSpeciesId }
            .toSet(),
    )
    assertEquals(3, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_DETAILS).size)
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesDeletedEvent && it.biomassSpeciesId == orphanBiomassSpeciesId
    }
  }

  @Test
  fun `rejects invalid identity and missing species`() {
    assertThrows<IllegalArgumentException> { deleteQuadratSpecies(null) }
    assertThrows<BiomassSpeciesNotFoundException> { deleteQuadratSpecies(insertSpecies()) }
  }

  @Test
  fun `rejects users without quantity permission`() {
    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> { deleteQuadratSpecies(null) }
  }

  private fun deleteQuadratSpecies(
      speciesId: SpeciesId?,
      position: ObservationPlotPosition = ObservationPlotPosition.SouthwestCorner,
  ) =
      store.deleteBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          position,
          speciesId,
          null,
      )
}
