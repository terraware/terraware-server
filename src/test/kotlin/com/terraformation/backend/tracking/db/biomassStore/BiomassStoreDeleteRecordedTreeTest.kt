package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_ADDITIONAL_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.db.RecordedTreeNotFoundException
import com.terraformation.backend.tracking.event.BiomassSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.RecordedTreeDeletedEvent
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreDeleteRecordedTreeTest : BaseBiomassStoreTest() {
  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var observationId: ObservationId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUpObservation() {
    monitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    speciesId = insertSpecies(scientificName = "Tree species")

    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `deletes an unreferenced biomass species and publishes both deletion events`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val recordedTreeId = insertTree(biomassSpeciesId)

    store.deleteRecordedTree(observationId, monitoringPlotId, recordedTreeId)

    assertTableEmpty(RECORDED_TREES)
    assertTableEmpty(OBSERVATION_BIOMASS_SPECIES)
    eventPublisher.assertExactEventsPublished(
        listOf(
            RecordedTreeDeletedEvent(
                biomassSpeciesId = biomassSpeciesId,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                recordedTreeId = recordedTreeId,
                speciesId = speciesId,
                speciesName = "Tree species",
                treeGrowthForm = TreeGrowthForm.Tree,
                treeNumber = 1,
                trunkNumber = 1,
            ),
            BiomassSpeciesDeletedEvent(
                biomassSpeciesId = biomassSpeciesId,
                commonName = null,
                isImplicit = true,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                scientificName = null,
                speciesId = speciesId,
                speciesName = "Tree species",
            ),
        )
    )
  }

  @Test
  fun `keeps a biomass species while a tree or quadrat entry references it`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val deletedTreeId = insertTree(biomassSpeciesId)
    val remainingTreeId = insertTree(biomassSpeciesId, treeNumber = 2)

    val expectedSpecies = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    store.deleteRecordedTree(observationId, monitoringPlotId, deletedTreeId)

    assertTableEquals(expectedSpecies, "After deleting first tree")

    insertObservationBiomassQuadratDetails()
    insertObservationBiomassQuadratSpecies(biomassSpeciesId = biomassSpeciesId)

    val expectedSpeciesWithQuadratEntry = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    val expectedQuadratSpecies = dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
    store.deleteRecordedTree(observationId, monitoringPlotId, remainingTreeId)

    assertTableEquals(
        expectedSpeciesWithQuadratEntry,
        "Biomass species after deleting last tree with quadrat species remaining",
    )
    assertTableEquals(expectedQuadratSpecies, "Quadrat species after deleting last tree")
  }

  @Test
  fun `keeps a biomass species that is an additional species`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val recordedTreeId = insertTree(biomassSpeciesId)
    insertObservationBiomassAdditionalSpecies()

    val expectedSpecies = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    val expectedAdditionalSpecies = dslContext.fetch(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES)
    store.deleteRecordedTree(observationId, monitoringPlotId, recordedTreeId)

    assertTableEmpty(RECORDED_TREES)
    assertTableEquals(expectedSpecies, "Biomass species")
    assertTableEquals(expectedAdditionalSpecies, "Additional species")
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `keeps a biomass species referenced by a tree in another plot`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val remainingTreeId = insertTree(biomassSpeciesId)

    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot(monitoringPlotId = otherMonitoringPlotId)
    insertObservationBiomassDetails(monitoringPlotId = otherMonitoringPlotId)
    val deletedTreeId = insertTree(biomassSpeciesId, treeNumber = 2)

    val expectedSpecies = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    store.deleteRecordedTree(observationId, otherMonitoringPlotId, deletedTreeId)

    assertTableEquals(expectedSpecies, "Biomass species table")
    assertEquals(
        remainingTreeId,
        dslContext.fetchSingle(RECORDED_TREES).id,
        "Tree in the first plot should remain",
    )
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `rejects wrong targets and users without quantity permission`() {
    val recordedTreeId = insertTree(insertObservationBiomassSpecies(speciesId = speciesId))

    assertThrows<RecordedTreeNotFoundException> {
      store.deleteRecordedTree(insertObservation(), monitoringPlotId, recordedTreeId)
    }
    assertThrows<RecordedTreeNotFoundException> {
      store.deleteRecordedTree(observationId, insertMonitoringPlot(isAdHoc = true), recordedTreeId)
    }

    every { user.canUpdateObservationQuantities(observationId) } returns false
    assertThrows<AccessDeniedException> {
      store.deleteRecordedTree(observationId, monitoringPlotId, recordedTreeId)
    }
  }

  private fun insertTree(biomassSpeciesId: BiomassSpeciesId, treeNumber: Int = 1) =
      insertRecordedTree(
          biomassSpeciesId = biomassSpeciesId,
          diameterAtBreastHeightCm = BigDecimal.ONE,
          heightM = BigDecimal.TEN,
          pointOfMeasurementM = BigDecimal("1.3"),
          treeNumber = treeNumber,
      )
}
