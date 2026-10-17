package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.db.RecordedTreeNotFoundException
import com.terraformation.backend.tracking.event.BiomassSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.RecordedTreeDeletedEvent
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
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
  fun `does not reuse the number of a deleted highest tree`() {
    val model =
        NewRecordedTreeModel(
            id = null,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            gpsCoordinates = null,
            heightM = BigDecimal.TEN,
            isDead = false,
            pointOfMeasurementM = BigDecimal("1.3"),
            speciesId = speciesId,
            treeGrowthForm = TreeGrowthForm.Tree,
            treeNumber = 1,
            trunkNumber = 1,
        )

    repeat(2) { store.createRecordedTree(observationId, monitoringPlotId, model) }

    val highestTreeId = dslContext.fetch(RECORDED_TREES).maxBy { it.treeNumber!! }.id!!
    store.deleteRecordedTree(observationId, monitoringPlotId, highestTreeId)
    store.createRecordedTree(observationId, monitoringPlotId, model)

    val treeNumbers = dslContext.fetch(RECORDED_TREES).map { it.treeNumber!! }.sorted()
    assertEquals(listOf(1, 3), treeNumbers)
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

    store.deleteRecordedTree(observationId, monitoringPlotId, deletedTreeId)

    assertEquals(1, dslContext.fetch(RECORDED_TREES).size)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)

    insertObservationBiomassQuadratDetails()
    insertObservationBiomassQuadratSpecies(biomassSpeciesId = biomassSpeciesId)

    store.deleteRecordedTree(observationId, monitoringPlotId, remainingTreeId)

    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
  }

  @Test
  fun `rejects wrong targets and users without quantity permission`() {
    val recordedTreeId = insertTree(insertObservationBiomassSpecies(speciesId = speciesId))

    listOf(
            insertObservation() to monitoringPlotId,
            observationId to insertMonitoringPlot(isAdHoc = true),
        )
        .forEach {
          assertThrows<RecordedTreeNotFoundException> {
            store.deleteRecordedTree(it.first, it.second, recordedTreeId)
          }
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
