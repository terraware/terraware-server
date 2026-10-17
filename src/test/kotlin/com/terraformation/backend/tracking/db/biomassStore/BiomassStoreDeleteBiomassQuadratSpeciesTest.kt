package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.db.BiomassSpeciesNotFoundException
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesDeletedEvent
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreDeleteBiomassQuadratSpeciesTest : BaseBiomassStoreTest() {
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
  fun `deletes only the selected species at the selected position and publishes exact event`() {
    val targetSpeciesId = inserted.speciesId
    val targetBiomassSpeciesId = inserted.biomassSpeciesId
    val targetSpeciesName =
        dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(targetSpeciesId))!!
    val otherBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = insertSpecies())
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.SouthwestCorner)
    insertObservationBiomassQuadratDetails(position = ObservationPlotPosition.SoutheastCorner)
    insertObservationBiomassQuadratSpecies(
        position = ObservationPlotPosition.SouthwestCorner,
        biomassSpeciesId = targetBiomassSpeciesId,
        abundanceCount = 4,
    )
    insertObservationBiomassQuadratSpecies(
        position = ObservationPlotPosition.SoutheastCorner,
        biomassSpeciesId = targetBiomassSpeciesId,
        abundanceCount = 5,
    )
    insertObservationBiomassQuadratSpecies(
        position = ObservationPlotPosition.SouthwestCorner,
        biomassSpeciesId = otherBiomassSpeciesId,
        abundanceCount = 6,
    )

    store.deleteBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.SouthwestCorner,
        targetSpeciesId,
        null,
    )

    val remaining = dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
    assertEquals(2, remaining.size)
    assertTrue(
        remaining.any {
          it.positionId == ObservationPlotPosition.SouthwestCorner &&
              it.biomassSpeciesId == otherBiomassSpeciesId &&
              it.abundanceCount == 6
        }
    )
    assertTrue(
        remaining.any {
          it.positionId == ObservationPlotPosition.SoutheastCorner &&
              it.biomassSpeciesId == targetBiomassSpeciesId &&
              it.abundanceCount == 5
        }
    )
    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_DETAILS).size)
    eventPublisher.assertEventPublished(
        BiomassQuadratSpeciesDeletedEvent(
            biomassSpeciesId = targetBiomassSpeciesId,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            position = ObservationPlotPosition.SouthwestCorner,
            speciesId = targetSpeciesId,
            speciesName = targetSpeciesName,
        )
    )
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `cleans up only biomass species that are no longer referenced`() {
    val referencePosition = ObservationPlotPosition.SouthwestCorner
    val orphanPosition = ObservationPlotPosition.SoutheastCorner
    val orphanSpeciesId = insertSpecies()
    val orphanBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = orphanSpeciesId)
    val orphanSpeciesName =
        dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(orphanSpeciesId))!!
    insertObservationBiomassQuadratDetails(position = referencePosition)
    insertObservationBiomassQuadratDetails(position = orphanPosition)
    insertObservationBiomassQuadratSpecies(position = referencePosition)
    insertObservationBiomassQuadratSpecies(
        position = orphanPosition,
        biomassSpeciesId = orphanBiomassSpeciesId,
    )
    insertRecordedTree(
        biomassSpeciesId = inserted.biomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal("1.3"),
    )

    store.deleteBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        referencePosition,
        inserted.speciesId,
        null,
    )

    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(1, dslContext.fetch(RECORDED_TREES).size)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()

    store.deleteBiomassQuadratSpecies(
        inserted.observationId,
        inserted.monitoringPlotId,
        orphanPosition,
        orphanSpeciesId,
        null,
    )

    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(inserted.biomassSpeciesId, dslContext.fetchSingle(OBSERVATION_BIOMASS_SPECIES).id)
    assertEquals(0, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_DETAILS).size)
    eventPublisher.assertEventPublished(
        BiomassSpeciesDeletedEvent(
            biomassSpeciesId = orphanBiomassSpeciesId,
            commonName = null,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            scientificName = null,
            speciesId = orphanSpeciesId,
            speciesName = orphanSpeciesName,
        )
    )
  }

  @Test
  fun `rejects invalid identities and missing species or entry`() {
    assertThrows<IllegalArgumentException> {
      store.deleteBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          null,
          null,
      )
    }
    assertThrows<IllegalArgumentException> {
      store.deleteBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          inserted.speciesId,
          "Both values",
      )
    }
    assertThrows<BiomassSpeciesNotFoundException> {
      store.deleteBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          inserted.speciesId,
          null,
      )
    }
    val absentSpeciesId = insertSpecies()
    assertThrows<BiomassSpeciesNotFoundException> {
      store.deleteBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          absentSpeciesId,
          null,
      )
    }
  }

  @Test
  fun `rejects users without quantity permission`() {
    every { user.canUpdateObservationQuantities(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.deleteBiomassQuadratSpecies(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.SouthwestCorner,
          inserted.speciesId,
          null,
      )
    }
  }
}
