package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
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
    speciesId = insertSpecies()

    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `does not reuse the number of a deleted highest tree`() {
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()

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

    store.createRecordedTree(observationId, monitoringPlotId, model)
    store.createRecordedTree(observationId, monitoringPlotId, model)

    val highestTreeId = dslContext.fetch(RECORDED_TREES).maxBy { it.treeNumber!! }.id!!
    store.deleteRecordedTree(observationId, monitoringPlotId, highestTreeId)
    store.createRecordedTree(observationId, monitoringPlotId, model)

    assertEquals(
        listOf(1, 3),
        dslContext.fetch(RECORDED_TREES).mapNotNull { it.treeNumber }.sorted(),
    )
  }

  @Test
  fun `deletes an unreferenced biomass species and publishes both deletion events`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val recordedTreeId = insertRecordedTree(biomassSpeciesId = biomassSpeciesId)
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
  fun `keeps the biomass species while a trunk or quadrat entry references it`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val deletedTrunkId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            treeGrowthForm = TreeGrowthForm.Trunk,
            treeNumber = 4,
            trunkNumber = 1,
        )
    insertRecordedTree(
        biomassSpeciesId = biomassSpeciesId,
        treeGrowthForm = TreeGrowthForm.Trunk,
        treeNumber = 4,
        trunkNumber = 2,
    )

    store.deleteRecordedTree(observationId, monitoringPlotId, deletedTrunkId)

    val remainingTrunk = dslContext.fetchSingle(RECORDED_TREES)
    assertEquals(4 to 2, remainingTrunk.treeNumber to remainingTrunk.trunkNumber)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)

    insertObservationBiomassQuadratDetails()
    insertObservationBiomassQuadratSpecies(biomassSpeciesId = biomassSpeciesId)

    store.deleteRecordedTree(observationId, monitoringPlotId, remainingTrunk.id!!)

    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
  }

  @Test
  fun `throws not found when the tree belongs to another observation or plot`() {
    val recordedTreeId =
        insertRecordedTree(
            biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId),
        )
    val otherObservationId = insertObservation()
    val otherPlotId = insertMonitoringPlot(isAdHoc = true)

    listOf(otherObservationId to monitoringPlotId, observationId to otherPlotId).forEach {
        (targetObservationId, targetPlotId) ->
      assertThrows<RecordedTreeNotFoundException> {
        store.deleteRecordedTree(targetObservationId, targetPlotId, recordedTreeId)
      }
    }
  }

  @Test
  fun `rejects users without quantity permission`() {
    val recordedTreeId =
        insertRecordedTree(
            biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId),
        )
    every { user.canUpdateObservationQuantities(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.deleteRecordedTree(observationId, monitoringPlotId, recordedTreeId)
    }
  }
}
