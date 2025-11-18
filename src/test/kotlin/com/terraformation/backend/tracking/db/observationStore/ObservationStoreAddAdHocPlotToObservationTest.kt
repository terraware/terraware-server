package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.embeddables.pojos.ObservationPlotId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.tracking.event.ObservationPlotCreatedEvent
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreAddAdHocPlotToObservationTest : BaseObservationStoreTest() {
  @Test
  fun `inserts a non-permanent observation plot`() {
    insertPlantingZone()
    insertPlantingSubzone()
    val plotId = insertMonitoringPlot(isAdHoc = true)
    val observationId = insertObservation(isAdHoc = true)

    store.addAdHocPlotToObservation(observationId, plotId)

    assertEquals(
        ObservationPlotsRow(
            observationId = observationId,
            monitoringPlotId = plotId,
            createdBy = user.userId,
            createdTime = clock.instant,
            isPermanent = false,
            modifiedBy = user.userId,
            modifiedTime = clock.instant,
            statusId = ObservationPlotStatus.Unclaimed,
            monitoringPlotHistoryId = inserted.monitoringPlotHistoryId,
        ),
        observationPlotsDao
            .fetchByObservationPlotId(ObservationPlotId(observationId, plotId))
            .single(),
        "Observation plot row",
    )

    eventPublisher.assertEventPublished(
        ObservationPlotCreatedEvent(
            isPermanent = false,
            monitoringPlotHistoryId = inserted.monitoringPlotHistoryId,
            monitoringPlotId = plotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            plotNumber = 1,
        ),
    )
  }

  @Test
  fun `throws exception if plots belong to a different planting site`() {
    val observationId = insertObservation(isAdHoc = true)

    insertPlantingSite()
    insertPlantingZone()
    insertPlantingSubzone()
    val otherSitePlotId = insertMonitoringPlot(isAdHoc = true)

    assertThrows<IllegalStateException> {
      store.addAdHocPlotToObservation(observationId, otherSitePlotId)
    }
  }

  @Test
  fun `throws exception for a non-ad-hoc observation`() {
    val observationId = insertObservation(isAdHoc = false)

    insertPlantingZone()
    insertPlantingSubzone()
    val plotId = insertMonitoringPlot(isAdHoc = true)

    assertThrows<IllegalStateException> { store.addAdHocPlotToObservation(observationId, plotId) }
  }

  @Test
  fun `throws exception for a non-ad-hoc plot`() {
    val observationId = insertObservation(isAdHoc = true)

    insertPlantingZone()
    insertPlantingSubzone()
    val plotId = insertMonitoringPlot(isAdHoc = false)

    assertThrows<IllegalStateException> { store.addAdHocPlotToObservation(observationId, plotId) }
  }

  @Test
  fun `throws exception if no permission to schedule ad-hoc observation`() {
    every { user.canScheduleAdHocObservation(plantingSiteId) } returns false

    val observationId = insertObservation(isAdHoc = true)

    insertPlantingZone()
    insertPlantingSubzone()
    val plotId = insertMonitoringPlot(isAdHoc = false)

    assertThrows<AccessDeniedException> { store.addAdHocPlotToObservation(observationId, plotId) }
  }
}
