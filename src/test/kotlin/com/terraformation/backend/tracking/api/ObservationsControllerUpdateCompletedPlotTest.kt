package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.BiomassStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationsControllerUpdateCompletedPlotTest {
  @Test
  fun `requires quantity permission only when a recorded tree species changes`() {
    val biomassStore = mockk<BiomassStore>(relaxed = true)
    val observationService = mockk<ObservationService>()
    val quantityUpdate = slot<Boolean>()
    val quantityUpdates = mutableListOf<Boolean>()
    val controller =
        ObservationsController(
            biomassStore = biomassStore,
            messages = mockk(relaxed = true),
            observationService = observationService,
            observationStore = mockk(relaxed = true),
            observationResultsStoreV2 = mockk(relaxed = true),
            plantingSiteStore = mockk(relaxed = true),
        )
    val observationId = ObservationId(1)
    val monitoringPlotId = MonitoringPlotId(2)
    val recordedTreeId = RecordedTreeId(3)
    val speciesId = SpeciesId(4)
    val speciesUpdate =
        RecordedTreeUpdateOperationPayload(
            boleHeight = null,
            description = null,
            diameterAtBreastHeight = null,
            height = null,
            isDead = null,
            recordedTreeId = recordedTreeId,
            speciesId = speciesId,
            pointOfMeasurement = null,
            shrubDiameter = null,
            treeCrownDiameter = null,
        )
    assertThrows<IllegalArgumentException> { speciesUpdate.copy(scientificName = "Other tree") }

    every {
      observationService.updateCompletedPlot<Unit>(
          any(),
          any(),
          capture(quantityUpdate),
          any(),
      )
    } answers
        {
          quantityUpdates.add(quantityUpdate.captured)
          arg<() -> Unit>(3).invoke()
        }

    controller.updateCompletedObservationPlot(
        observationId,
        monitoringPlotId,
        UpdateObservationRequestPayload(listOf(speciesUpdate)),
    )
    controller.updateCompletedObservationPlot(
        observationId,
        monitoringPlotId,
        UpdateObservationRequestPayload(listOf(speciesUpdate.copy(speciesId = null))),
    )

    assertEquals(listOf(true, false), quantityUpdates)
    verify(exactly = 1) {
      biomassStore.changeRecordedTreeSpecies(
          observationId,
          monitoringPlotId,
          recordedTreeId,
          speciesId,
          null,
      )
    }
  }
}
