package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
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
    insertStratum()
    insertSubstratum()
    monitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    speciesId = insertSpecies()

    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `deletes only one trunk and preserves the remaining trunk number`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val deletedTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal("1.3"),
            treeGrowthForm = TreeGrowthForm.Trunk,
            treeNumber = 4,
            trunkNumber = 1,
        )
    val remainingTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal("2"),
            pointOfMeasurementM = BigDecimal("1.5"),
            treeGrowthForm = TreeGrowthForm.Trunk,
            treeNumber = 4,
            trunkNumber = 2,
        )
    val speciesName = dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(speciesId))!!

    store.deleteRecordedTree(observationId, monitoringPlotId, deletedTreeId)

    val remaining = dslContext.fetchSingle(RECORDED_TREES)
    assertEquals(remainingTreeId, remaining.id)
    assertEquals(4, remaining.treeNumber)
    assertEquals(2, remaining.trunkNumber)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    eventPublisher.assertEventPublished(
        RecordedTreeDeletedEvent(
            biomassSpeciesId = biomassSpeciesId,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            recordedTreeId = deletedTreeId,
            speciesId = speciesId,
            speciesName = speciesName,
            treeGrowthForm = TreeGrowthForm.Trunk,
            treeNumber = 4,
            trunkNumber = 1,
        )
    )
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `does not reuse the number of a deleted highest tree`() {
    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())
    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

    val highestTreeId = dslContext.fetch(RECORDED_TREES).maxBy { it.treeNumber!! }.id!!
    store.deleteRecordedTree(observationId, monitoringPlotId, highestTreeId)
    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

    assertEquals(
        listOf(1, 3),
        dslContext.fetch(RECORDED_TREES).mapNotNull { it.treeNumber }.sorted(),
    )
    assertEquals(3, dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS).highestTreeNumber)
  }

  @Test
  fun `deletes an unreferenced biomass species and publishes both deletion events`() {
    val biomassSpeciesId =
        insertObservationBiomassSpecies(
            speciesId = speciesId,
            isInvasive = true,
            isThreatened = true,
        )
    val recordedTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal("1.3"),
        )
    val speciesName = dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(speciesId))!!

    store.deleteRecordedTree(observationId, monitoringPlotId, recordedTreeId)

    assertEquals(0, dslContext.fetch(RECORDED_TREES).size)
    assertEquals(0, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    eventPublisher.assertEventPublished(
        RecordedTreeDeletedEvent(
            biomassSpeciesId = biomassSpeciesId,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            recordedTreeId = recordedTreeId,
            speciesId = speciesId,
            speciesName = speciesName,
            treeGrowthForm = TreeGrowthForm.Tree,
            treeNumber = 1,
            trunkNumber = 1,
        )
    )
    eventPublisher.assertEventPublished(
        BiomassSpeciesDeletedEvent(
            biomassSpeciesId = biomassSpeciesId,
            commonName = null,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            scientificName = null,
            speciesId = speciesId,
            speciesName = speciesName,
        )
    )
  }

  @Test
  fun `keeps the biomass species while another tree references it`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val deletedTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal("1.3"),
            treeNumber = 1,
        )
    insertRecordedTree(
        biomassSpeciesId = biomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal("2"),
        pointOfMeasurementM = BigDecimal("1.4"),
        treeNumber = 2,
    )

    store.deleteRecordedTree(observationId, monitoringPlotId, deletedTreeId)

    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `keeps the biomass species while a quadrat entry references it`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val recordedTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal("1.3"),
        )
    insertObservationBiomassQuadratDetails(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
    )
    insertObservationBiomassQuadratSpecies(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
        biomassSpeciesId = biomassSpeciesId,
    )

    store.deleteRecordedTree(observationId, monitoringPlotId, recordedTreeId)

    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `throws not found when the tree belongs to another observation or plot`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val recordedTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal("1.3"),
        )
    val otherObservationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    val otherPlotId = insertMonitoringPlot(isAdHoc = true)

    assertThrows<RecordedTreeNotFoundException> {
      store.deleteRecordedTree(otherObservationId, monitoringPlotId, recordedTreeId)
    }
    assertThrows<RecordedTreeNotFoundException> {
      store.deleteRecordedTree(observationId, otherPlotId, recordedTreeId)
    }
  }

  @Test
  fun `rejects users without quantity permission`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val recordedTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal("1.3"),
        )
    every { user.canUpdateObservationQuantities(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.deleteRecordedTree(observationId, monitoringPlotId, recordedTreeId)
    }
  }

  private fun newTreeModel() =
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
}
