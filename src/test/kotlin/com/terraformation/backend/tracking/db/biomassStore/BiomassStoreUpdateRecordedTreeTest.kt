package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEventValues
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreUpdateRecordedTreeTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpRecordedTree() {
    insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    insertSpecies()
    insertObservationBiomassSpecies(speciesId = inserted.speciesId)
  }

  @Test
  fun `updates editable fields for shrubs`() {
    insertRecordedTree(
        description = "Original description",
        shrubDiameterCm = 1,
        treeGrowthForm = TreeGrowthForm.Shrub,
    )

    val before = dslContext.fetchSingle(RECORDED_TREES)

    store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) {
      it.copy(
          description = "New description",
          isDead = true,
          treeGrowthForm = TreeGrowthForm.Tree, // Shouldn't be editable
          shrubDiameterCm = 2,
          speciesName = "New species", // Shouldn't be editable
      )
    }

    val expected =
        before.copy().apply {
          description = "New description"
          isDead = true
          shrubDiameterCm = 2
        }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        RecordedTreeUpdatedEvent(
            changedFrom =
                RecordedTreeUpdatedEventValues(
                    description = "Original description",
                    isDead = false,
                    shrubDiameterCm = 1,
                ),
            changedTo =
                RecordedTreeUpdatedEventValues(
                    description = "New description",
                    isDead = true,
                    shrubDiameterCm = 2,
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            recordedTreeId = inserted.recordedTreeId,
        )
    )
  }

  @Test
  fun `updates editable fields for trees`() {
    insertRecordedTree(
        description = "Original description",
        diameterAtBreastHeightCm = BigDecimal(1),
        heightM = BigDecimal(2),
        pointOfMeasurementM = BigDecimal(3),
        treeGrowthForm = TreeGrowthForm.Tree,
    )

    val before = dslContext.fetchSingle(RECORDED_TREES)

    store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) {
      it.copy(
          description = "New description",
          diameterAtBreastHeightCm = BigDecimal(4),
          heightM = BigDecimal(5),
          isDead = true,
          pointOfMeasurementM = BigDecimal(6),
      )
    }

    val expected =
        before.copy().apply {
          description = "New description"
          diameterAtBreastHeightCm = BigDecimal(4)
          heightM = BigDecimal(5)
          isDead = true
          pointOfMeasurementM = BigDecimal(6)
        }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        RecordedTreeUpdatedEvent(
            changedFrom =
                RecordedTreeUpdatedEventValues(
                    description = "Original description",
                    diameterAtBreastHeightCm = BigDecimal(1),
                    heightM = BigDecimal(2),
                    isDead = false,
                    pointOfMeasurementM = BigDecimal(3),
                ),
            changedTo =
                RecordedTreeUpdatedEventValues(
                    description = "New description",
                    diameterAtBreastHeightCm = BigDecimal(4),
                    heightM = BigDecimal(5),
                    isDead = true,
                    pointOfMeasurementM = BigDecimal(6),
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            recordedTreeId = inserted.recordedTreeId,
        )
    )
  }

  @Test
  fun `does not publish event or modify database if nothing changed`() {
    insertRecordedTree(shrubDiameterCm = 1, treeGrowthForm = TreeGrowthForm.Shrub)

    val unmodifiedTable = dslContext.fetch(RECORDED_TREES)

    store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) { it }

    assertTableEquals(unmodifiedTable)

    eventPublisher.assertEventNotPublished<RecordedTreeUpdatedEvent>()
  }

  @Test
  fun `throws exception if editing value for wrong kind of growth form`() {
    insertRecordedTree(shrubDiameterCm = 1, treeGrowthForm = TreeGrowthForm.Shrub)

    assertThrows<IllegalArgumentException> {
      store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) {
        it.copy(heightM = BigDecimal.ONE)
      }
    }
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    insertRecordedTree(shrubDiameterCm = 1, treeGrowthForm = TreeGrowthForm.Shrub)

    every { user.canUpdateObservation(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) {
        it.copy(description = "New description")
      }
    }
  }
}
