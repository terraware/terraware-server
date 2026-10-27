package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassDetailsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassAdditionalSpeciesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.point
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Point
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
    speciesId = insertSpecies()

    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `numbers trees using the stored counter and only trees in the plot`() {
    insertObservationBiomassDetails(
        row = ObservationBiomassDetailsRow(nextTreeNumber = 6),
    )
    insertObservationBiomassSpecies(speciesId = speciesId)

    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot(monitoringPlotId = otherMonitoringPlotId, completedBy = user.userId)
    insertObservationBiomassDetails(
        monitoringPlotId = otherMonitoringPlotId,
        row = ObservationBiomassDetailsRow(nextTreeNumber = 8),
    )

    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

    assertEquals(
        mapOf(monitoringPlotId to listOf(6)),
        dslContext
            .fetch(RECORDED_TREES)
            .groupBy { it.monitoringPlotId }
            .mapValues { (_, trees) -> trees.mapNotNull { it.treeNumber }.sorted() },
        "Tree numbers by plot",
    )
    val details = dslContext.fetch(OBSERVATION_BIOMASS_DETAILS)
    assertEquals(
        7,
        details.single { it.monitoringPlotId == monitoringPlotId }.nextTreeNumber,
        "Next tree number of plot with new tree",
    )
    assertEquals(
        8,
        details.single { it.monitoringPlotId == otherMonitoringPlotId }.nextTreeNumber,
        "Next tree number of other plot",
    )
  }

  @Test
  fun `reuses and creates known and Other biomass species for trees`() {
    insertObservationBiomassDetails()
    val existingBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val gpsCoordinates = point(1)

    store.createRecordedTree(
        observationId,
        monitoringPlotId,
        newTreeModel(
            gpsCoordinates = gpsCoordinates,
            heightM = null,
            diameterAtBreastHeightCm = null,
            pointOfMeasurementM = null,
            shrubDiameterCm = 31,
            treeGrowthForm = TreeGrowthForm.Shrub,
        ),
    )

    val shrubRecord = dslContext.fetchSingle(RECORDED_TREES)

    assertEquals(gpsCoordinates, shrubRecord.gpsCoordinates, "GPS coordinates")
    assertEquals(
        existingBiomassSpeciesId,
        shrubRecord.biomassSpeciesId,
        "Shrub should reuse existing biomass species",
    )
    assertEquals(31, shrubRecord.shrubDiameterCm, "Shrub diameter")

    eventPublisher.assertEventNotPublished<BiomassSpeciesCreatedEvent>()
    eventPublisher.assertEventPublished(
        RecordedTreeCreatedEvent(
            biomassSpeciesId = shrubRecord.biomassSpeciesId!!,
            gpsCoordinates = gpsCoordinates,
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

    val otherKnownSpeciesId = insertSpecies()
    listOf(
            newTreeModel(speciesId = otherKnownSpeciesId),
            newTreeModel(speciesId = null, speciesName = "Other tree"),
        )
        .forEach { store.createRecordedTree(observationId, monitoringPlotId, it) }

    val speciesRecords = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    assertEquals(
        setOf(speciesId to null, otherKnownSpeciesId to null, null to "Other tree"),
        speciesRecords.map { it.speciesId to it.scientificName }.toSet(),
        "Biomass species (species ID to scientific name)",
    )
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.speciesId == otherKnownSpeciesId
    }
    eventPublisher.assertEventPublished {
      it is BiomassSpeciesCreatedEvent && it.speciesName == "Other tree"
    }
  }

  @Test
  fun `removes species from additional species of only the tree's plot`() {
    insertObservationBiomassDetails()
    insertObservationBiomassSpecies(speciesId = speciesId)
    insertObservationBiomassAdditionalSpecies()

    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot(monitoringPlotId = otherMonitoringPlotId, completedBy = user.userId)
    insertObservationBiomassDetails(monitoringPlotId = otherMonitoringPlotId)
    val otherPlotBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    insertObservationBiomassAdditionalSpecies()

    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

    assertTableEquals(
        ObservationBiomassAdditionalSpeciesRecord(
            biomassSpeciesId = otherPlotBiomassSpeciesId,
            monitoringPlotId = otherMonitoringPlotId,
            observationId = observationId,
        ),
        "Additional species after creating tree",
    )
  }

  @Test
  fun `rejects invalid species and measurements`() {
    insertObservationBiomassDetails()
    listOf(
            newTreeModel(speciesId = null, speciesName = null),
            newTreeModel(treeGrowthForm = TreeGrowthForm.Trunk),
            newTreeModel(diameterAtBreastHeightCm = null),
            newTreeModel(heightM = null),
            newTreeModel(diameterAtBreastHeightCm = BigDecimal("-1")),
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
    insertObservationBiomassDetails()
    every { user.canUpdateObservationQuantities(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())
    }
  }

  private fun newTreeModel(
      speciesId: SpeciesId? = this.speciesId,
      speciesName: String? = null,
      treeGrowthForm: TreeGrowthForm = TreeGrowthForm.Tree,
      gpsCoordinates: Point? = null,
      diameterAtBreastHeightCm: BigDecimal? = BigDecimal.ONE,
      heightM: BigDecimal? = BigDecimal.TEN,
      pointOfMeasurementM: BigDecimal? = BigDecimal("1.3"),
      shrubDiameterCm: Int? = null,
  ): NewRecordedTreeModel =
      NewRecordedTreeModel(
          id = null,
          diameterAtBreastHeightCm = diameterAtBreastHeightCm,
          gpsCoordinates = gpsCoordinates,
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
