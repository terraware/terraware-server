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
    listOf(1, 3).forEach {
      insertRecordedTree(
          biomassSpeciesId = biomassSpeciesId,
          diameterAtBreastHeightCm = BigDecimal.ONE,
          heightM = BigDecimal.ONE,
          pointOfMeasurementM = BigDecimal.ONE,
          treeNumber = it,
      )
    }

    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot(monitoringPlotId = otherMonitoringPlotId, completedBy = user.userId)
    insertObservationBiomassDetails(monitoringPlotId = otherMonitoringPlotId)
    val otherBiomassSpeciesId =
        insertObservationBiomassSpecies(
            monitoringPlotId = otherMonitoringPlotId,
            speciesId = speciesId,
        )
    insertRecordedTree(
        biomassSpeciesId = otherBiomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        heightM = BigDecimal.ONE,
        monitoringPlotId = otherMonitoringPlotId,
        pointOfMeasurementM = BigDecimal.ONE,
        treeNumber = 10,
    )

    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

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
        mapOf(monitoringPlotId to listOf(1, 3, 4, 6), otherMonitoringPlotId to listOf(10)),
        dslContext
            .fetch(RECORDED_TREES)
            .groupBy { it.monitoringPlotId }
            .mapValues { (_, trees) -> trees.mapNotNull { it.treeNumber }.sorted() },
    )
    val details = dslContext.fetch(OBSERVATION_BIOMASS_DETAILS)
    assertEquals(6, details.single { it.monitoringPlotId == monitoringPlotId }.highestTreeNumber)
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
    assertEquals(31, shrubRecord.shrubDiameterCm)

    eventPublisher.assertEventNotPublished<BiomassSpeciesCreatedEvent>()
    eventPublisher.assertEventPublished(
        RecordedTreeCreatedEvent(
            biomassSpeciesId = shrubRecord.biomassSpeciesId!!,
            isDead = false,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            recordedTreeId = shrubRecord.id!!,
            shrubDiameterCm = 31,
            speciesId = speciesId,
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
    assertEquals(
        setOf(speciesId to null, otherCatalogSpeciesId to null, null to "Other tree"),
        speciesRecords.map { it.speciesId to it.scientificName }.toSet(),
    )
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.speciesId == otherCatalogSpeciesId
    }
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.speciesName == "Other tree"
    }
  }

  @Test
  fun `rejects invalid species and measurements`() {
    listOf(
            newTreeModel(speciesId = null, speciesName = null),
            newTreeModel(treeGrowthForm = TreeGrowthForm.Trunk),
            newTreeModel(diameterAtBreastHeightCm = null),
            newTreeModel(shrubDiameterCm = 12),
            newTreeModel(treeGrowthForm = TreeGrowthForm.Shrub),
        )
        .forEach { model ->
          assertThrows<IllegalArgumentException> {
            store.createRecordedTree(observationId, monitoringPlotId, model)
          }
        }

    val foreignSpeciesId = insertSpecies(organizationId = insertOrganization())
    assertThrows<SpeciesNotFoundException> {
      store.createRecordedTree(
          observationId,
          monitoringPlotId,
          newTreeModel(speciesId = foreignSpeciesId),
      )
    }
    assertTableEmpty(RECORDED_TREES)
    assertTableEmpty(OBSERVATION_BIOMASS_SPECIES)
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
      diameterAtBreastHeightCm: BigDecimal? = BigDecimal.ONE,
      heightM: BigDecimal? = BigDecimal.TEN,
      pointOfMeasurementM: BigDecimal? = BigDecimal("1.3"),
      shrubDiameterCm: Int? = null,
  ): NewRecordedTreeModel =
      NewRecordedTreeModel(
          id = null,
          diameterAtBreastHeightCm = diameterAtBreastHeightCm,
          gpsCoordinates = null,
          heightM = heightM,
          isDead = false,
          pointOfMeasurementM = pointOfMeasurementM,
          shrubDiameterCm = shrubDiameterCm,
          speciesId = speciesId,
          speciesName = speciesName,
          treeGrowthForm = treeGrowthForm,
          treeNumber = 1,
          trunkNumber = 1,
      )
}
