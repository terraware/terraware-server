package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.db.ObservationPlotNotFoundException
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
    insertRecordedTree(
        biomassSpeciesId = biomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal.ONE,
        treeNumber = 1,
    )
    val secondTreeId =
        insertRecordedTree(
            biomassSpeciesId = biomassSpeciesId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal.ONE,
            treeNumber = 2,
        )
    insertRecordedTree(
        biomassSpeciesId = biomassSpeciesId,
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal.ONE,
        treeNumber = 3,
    )

    dslContext.deleteFrom(RECORDED_TREES).where(RECORDED_TREES.ID.eq(secondTreeId)).execute()

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
        diameterAtBreastHeightCm = BigDecimal.ONE,
        pointOfMeasurementM = BigDecimal.ONE,
        treeNumber = 10,
    )

    store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())

    assertEquals(
        listOf(1, 3, 4),
        dslContext
            .select(RECORDED_TREES.TREE_NUMBER)
            .from(RECORDED_TREES)
            .where(RECORDED_TREES.OBSERVATION_ID.eq(observationId))
            .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .fetch()
            .mapNotNull { it.value1() }
            .sorted(),
    )
    assertEquals(
        10,
        dslContext
            .select(RECORDED_TREES.TREE_NUMBER)
            .from(RECORDED_TREES)
            .where(RECORDED_TREES.OBSERVATION_ID.eq(observationId))
            .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(otherMonitoringPlotId))
            .fetchSingle()
            .value1(),
    )

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
        dslContext
            .select(RECORDED_TREES.TREE_NUMBER)
            .from(RECORDED_TREES)
            .where(RECORDED_TREES.OBSERVATION_ID.eq(observationId))
            .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .fetch()
            .maxOf { it.value1() ?: 0 },
    )
    assertEquals(
        6,
        dslContext
            .select(OBSERVATION_BIOMASS_DETAILS.HIGHEST_TREE_NUMBER)
            .from(OBSERVATION_BIOMASS_DETAILS)
            .where(
                OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(observationId)
                    .and(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            )
            .fetchSingle()
            .value1(),
    )
  }

  @Test
  fun `throws if the observation plot has no biomass details`() {
    dslContext
        .deleteFrom(OBSERVATION_BIOMASS_DETAILS)
        .where(
            OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(observationId)
                .and(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId))
        )
        .execute()

    assertThrows<ObservationPlotNotFoundException> {
      store.createRecordedTree(observationId, monitoringPlotId, newTreeModel())
    }
  }

  @Test
  fun `reuses and creates catalog and Other biomass species for trees`() {
    val existingBiomassSpeciesId =
        insertObservationBiomassSpecies(
            speciesId = speciesId,
            isInvasive = true,
            isThreatened = true,
        )

    store.createRecordedTree(
        observationId,
        monitoringPlotId,
        newTreeModel(
            description = "New shrub",
            heightM = null,
            isDead = true,
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
    assertEquals(null, shrubRecord.diameterAtBreastHeightCm)
    assertEquals(null, shrubRecord.pointOfMeasurementM)
    assertEquals(null, shrubRecord.heightM)
    assertEquals(1, shrubRecord.treeNumber)
    assertEquals(1, shrubRecord.trunkNumber)
    assertEquals(null, shrubRecord.gpsCoordinates)

    eventPublisher.assertEventNotPublished<BiomassSpeciesCreatedEvent>()
    eventPublisher.assertEventPublished(
        RecordedTreeCreatedEvent(
            biomassSpeciesId = shrubRecord.biomassSpeciesId!!,
            boleHeightM = null,
            description = "New shrub",
            diameterAtBreastHeightCm = null,
            gpsCoordinates = null,
            heightM = null,
            isDead = true,
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
    val catalogSpeciesName =
        dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(otherCatalogSpeciesId))!!

    store.createRecordedTree(
        observationId,
        monitoringPlotId,
        newTreeModel(speciesId = otherCatalogSpeciesId),
    )
    store.createRecordedTree(
        observationId,
        monitoringPlotId,
        newTreeModel(speciesId = null, speciesName = "Other tree"),
    )

    val catalogBiomassSpecies =
        dslContext
            .selectFrom(OBSERVATION_BIOMASS_SPECIES)
            .where(OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(otherCatalogSpeciesId))
            .fetchSingle()
    val otherBiomassSpecies =
        dslContext
            .selectFrom(OBSERVATION_BIOMASS_SPECIES)
            .where(OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME.eq("Other tree"))
            .fetchSingle()

    assertEquals(false, catalogBiomassSpecies.isInvasive)
    assertEquals(false, catalogBiomassSpecies.isThreatened)
    assertEquals(null, catalogBiomassSpecies.commonName)
    assertEquals(false, otherBiomassSpecies.isInvasive)
    assertEquals(false, otherBiomassSpecies.isThreatened)
    assertEquals(null, otherBiomassSpecies.commonName)

    eventPublisher.assertEventPublished(
        BiomassSpeciesCreatedEvent(
            biomassSpeciesId = catalogBiomassSpecies.id!!,
            commonName = null,
            isInvasive = false,
            isThreatened = false,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            scientificName = null,
            speciesId = otherCatalogSpeciesId,
            speciesName = catalogSpeciesName,
        )
    )
    eventPublisher.assertEventPublished(
        BiomassSpeciesCreatedEvent(
            biomassSpeciesId = otherBiomassSpecies.id!!,
            commonName = null,
            isInvasive = false,
            isThreatened = false,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            scientificName = "Other tree",
            speciesId = null,
            speciesName = "Other tree",
        )
    )
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
            newTreeModel(pointOfMeasurementM = null),
            newTreeModel(shrubDiameterCm = 12),
            newTreeModel(
                diameterAtBreastHeightCm = null,
                heightM = null,
                pointOfMeasurementM = null,
                shrubDiameterCm = null,
                treeGrowthForm = TreeGrowthForm.Shrub,
            ),
            newTreeModel(
                diameterAtBreastHeightCm = BigDecimal.ONE,
                pointOfMeasurementM = null,
                shrubDiameterCm = 12,
                treeGrowthForm = TreeGrowthForm.Shrub,
            ),
            newTreeModel(
                heightM = BigDecimal.ONE,
                diameterAtBreastHeightCm = null,
                pointOfMeasurementM = null,
                shrubDiameterCm = 12,
                treeGrowthForm = TreeGrowthForm.Shrub,
            ),
            newTreeModel(
                heightM = null,
                diameterAtBreastHeightCm = null,
                pointOfMeasurementM = BigDecimal.ONE,
                shrubDiameterCm = 12,
                treeGrowthForm = TreeGrowthForm.Shrub,
            ),
            newTreeModel(
                boleHeightM = BigDecimal.ONE,
                diameterAtBreastHeightCm = null,
                heightM = null,
                pointOfMeasurementM = null,
                shrubDiameterCm = 12,
                treeGrowthForm = TreeGrowthForm.Shrub,
            ),
            newTreeModel(
                diameterAtBreastHeightCm = null,
                heightM = null,
                pointOfMeasurementM = null,
                shrubDiameterCm = 12,
                treeCrownDiameterCm = 20,
                treeGrowthForm = TreeGrowthForm.Shrub,
            ),
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
