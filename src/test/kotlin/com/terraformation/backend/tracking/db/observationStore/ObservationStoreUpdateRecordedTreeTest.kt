package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEventValues
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreUpdateRecordedTreeTest : BaseObservationStoreTest() {
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
    insertRecordedTree(
        description = "Original description",
        shrubDiameterCm = 1,
        treeGrowthForm = TreeGrowthForm.Shrub,
    )
  }

  @Test
  fun `updates editable fields`() {
    val before = dslContext.fetchSingle(RECORDED_TREES)

    store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) {
      it.copy(
          description = "New description",
          treeGrowthForm = TreeGrowthForm.Tree,
          shrubDiameterCm = null,
          speciesName = "New species",
      )
    }

    val expected = before.copy().apply { description = "New description" }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        RecordedTreeUpdatedEvent(
            changedFrom = RecordedTreeUpdatedEventValues(description = "Original description"),
            changedTo = RecordedTreeUpdatedEventValues(description = "New description"),
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
    val unmodifiedTable = dslContext.fetch(RECORDED_TREES)

    store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) { it }

    assertTableEquals(unmodifiedTable)

    eventPublisher.assertEventNotPublished<RecordedTreeUpdatedEvent>()
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    every { user.canUpdateObservation(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateRecordedTree(inserted.observationId, inserted.recordedTreeId) {
        it.copy(description = "New description")
      }
    }
  }
}
