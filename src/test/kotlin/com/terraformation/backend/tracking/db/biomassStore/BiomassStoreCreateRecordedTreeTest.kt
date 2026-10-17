package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreCreateRecordedTreeTest : BaseBiomassStoreTest() {
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
  fun `numbers trees using the stored counter and only trees in the plot`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    (1..3).forEach { treeNumber ->
      insertRecordedTree(biomassSpeciesId = biomassSpeciesId, treeNumber = treeNumber)
    }
    dslContext
        .deleteFrom(RECORDED_TREES)
        .where(RECORDED_TREES.TREE_NUMBER.eq(2))
        .execute()

    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = otherMonitoringPlotId,
        completedBy = user.userId,
    )
    insertObservationBiomassDetails(
        observationId = observationId,
        monitoringPlotId = otherMonitoringPlotId,
    )
    val otherBiomassSpeciesId =
        insertObservationBiomassSpecies(
            observationId = observationId,
            monitoringPlotId = otherMonitoringPlotId,
            speciesId = speciesId,
        )
    insertRecordedTree(
        biomassSpeciesId = otherBiomassSpeciesId,
        observationId = observationId,
        monitoringPlotId = otherMonitoringPlotId,
        treeNumber = 10,
    )

    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

    val treeNumbersByPlot =
        dslContext.fetch(RECORDED_TREES).groupBy { it.monitoringPlotId }.mapValues {
          (_, trees) -> trees.mapNotNull { it.treeNumber }.sorted()
        }
    assertEquals(listOf(1, 3, 4), treeNumbersByPlot[monitoringPlotId])
    assertEquals(listOf(10), treeNumbersByPlot[otherMonitoringPlotId])

    dslContext
        .update(OBSERVATION_BIOMASS_DETAILS)
        .set(OBSERVATION_BIOMASS_DETAILS.HIGHEST_TREE_NUMBER, 5)
        .where(
            OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(observationId)
                .and(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId))
        )
        .execute()
    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

    assertEquals(
        6,
        dslContext.fetch(RECORDED_TREES).filter { it.monitoringPlotId == monitoringPlotId }.maxOf {
          it.treeNumber!!
        },
    )
    assertEquals(
        6,
        dslContext
            .selectFrom(OBSERVATION_BIOMASS_DETAILS)
            .where(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .fetchSingle()
            .highestTreeNumber,
    )
  }

  @Test
  fun `reuses and creates catalog and Other biomass species for trees`() {
    val existingBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)

    store.createRecordedTree(
        observationId,
        monitoringPlotId,
        newTreeModel(
            heightM = null,
            diameterAtBreastHeightCm = null,
            pointOfMeasurementM = null,
            shrubDiameterCm = 31,
            treeGrowthForm = TreeGrowthForm.Shrub,
        ),
    )

    val shrubRecord = dslContext.fetchSingle(RECORDED_TREES)

    assertEquals(existingBiomassSpeciesId, shrubRecord.biomassSpeciesId)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(TreeGrowthForm.Shrub, shrubRecord.treeGrowthFormId)
    assertEquals(31, shrubRecord.shrubDiameterCm)

    eventPublisher.assertEventNotPublished<BiomassSpeciesCreatedEvent>()
    eventPublisher.assertEventPublished(
        RecordedTreeCreatedEvent(
            biomassSpeciesId = shrubRecord.biomassSpeciesId!!,
            boleHeightM = null,
            description = null,
            diameterAtBreastHeightCm = null,
            gpsCoordinates = null,
            heightM = null,
            isDead = false,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            pointOfMeasurementM = null,
            recordedTreeId = shrubRecord.id!!,
            shrubDiameterCm = 31,
            speciesId = speciesId,
            speciesName = null,
            treeCrownDiameterCm = null,
            treeGrowthForm = TreeGrowthForm.Shrub,
            treeNumber = 1,
            trunkNumber = 1,
        )
    )

    val otherCatalogSpeciesId = insertSpecies()
    listOf(
            newTreeModel(speciesId = otherCatalogSpeciesId),
            newTreeModel(speciesId = null, speciesName = "Other tree"),
        )
        .forEach { store.createRecordedTree(observationId, monitoringPlotId, it) }

    val speciesRecords = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    val catalogBiomassSpecies = speciesRecords.single { it.speciesId == otherCatalogSpeciesId }
    val otherBiomassSpecies = speciesRecords.single { it.scientificName == "Other tree" }

    assertEquals(otherCatalogSpeciesId, catalogBiomassSpecies.speciesId)
    assertEquals("Other tree", otherBiomassSpecies.scientificName)
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.speciesId == otherCatalogSpeciesId
    }
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.speciesName == "Other tree"
    }
  }

  @Test
  fun `requires exactly one species identifier from this organization`() {
    assertThrows<IllegalArgumentException> {
      store.createRecordedTree(
          observationId,
          monitoringPlotId,
          newTreeModel(speciesId = null, speciesName = null),
      )
    }
    assertThrows<IllegalArgumentException> {
      store.createRecordedTree(
          observationId,
          monitoringPlotId,
          newTreeModel(speciesName = "Other tree"),
      )
    }

    val foreignOrganizationId = insertOrganization()
    val foreignSpeciesId = insertSpecies(organizationId = foreignOrganizationId)
    assertThrows<SpeciesNotFoundException> {
      store.createRecordedTree(
          observationId,
          monitoringPlotId,
          newTreeModel(speciesId = foreignSpeciesId),
      )
    }
    assertTableEmpty(
        RECORDED_TREES,
        where = RECORDED_TREES.OBSERVATION_ID.eq(observationId),
    )
    assertTableEmpty(
        OBSERVATION_BIOMASS_SPECIES,
        where = OBSERVATION_BIOMASS_SPECIES.OBSERVATION_ID.eq(observationId),
    )
  }

  @Test
  fun `rejects invalid growth forms and measurements`() {
    val invalidModels =
        listOf(
            newTreeModel(treeGrowthForm = TreeGrowthForm.Trunk),
            newTreeModel(diameterAtBreastHeightCm = null),
            newTreeModel(shrubDiameterCm = 12),
            newTreeModel(treeGrowthForm = TreeGrowthForm.Shrub),
        )

    invalidModels.forEach { model ->
      assertThrows<IllegalArgumentException> {
        store.createRecordedTree(observationId, monitoringPlotId, model)
      }
    }
  }

  @Test
  fun `rejects users without quantity permission`() {
    every { user.canUpdateObservationQuantities(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())
    }
  }

  private fun newTreeModel(
      speciesId: SpeciesId? = this.speciesId,
      speciesName: String? = null,
      treeGrowthForm: TreeGrowthForm = TreeGrowthForm.Tree,
      isDead: Boolean = false,
      diameterAtBreastHeightCm: BigDecimal? = BigDecimal.ONE,
      pointOfMeasurementM: BigDecimal? = BigDecimal("1.3"),
      heightM: BigDecimal? = BigDecimal.TEN,
      shrubDiameterCm: Int? = null,
      treeCrownDiameterCm: Int? = null,
      boleHeightM: BigDecimal? = null,
      description: String? = null,
  ): NewRecordedTreeModel =
      NewRecordedTreeModel(
          id = null,
          boleHeightM = boleHeightM,
          description = description,
          diameterAtBreastHeightCm = diameterAtBreastHeightCm,
          gpsCoordinates = null,
          heightM = heightM,
          isDead = isDead,
          pointOfMeasurementM = pointOfMeasurementM,
          shrubDiameterCm = shrubDiameterCm,
          speciesId = speciesId,
          speciesName = speciesName,
          treeCrownDiameterCm = treeCrownDiameterCm,
          treeGrowthForm = treeGrowthForm,
          treeNumber = 1,
          trunkNumber = 1,
      )
}
