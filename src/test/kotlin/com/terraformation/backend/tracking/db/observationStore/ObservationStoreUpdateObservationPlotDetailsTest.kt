package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.tracking.db.PlotNotCompletedException
import com.terraformation.backend.tracking.event.ObservationPlotEditedEvent
import com.terraformation.backend.tracking.event.ObservationPlotEditedEventValues
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreUpdateObservationPlotDetailsTest : BaseObservationStoreTest() {
  @Test
  fun `updates editable fields and publishes event`() {
    val observationId = insertObservation()
    val monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot(completedBy = user.userId)

    val before = dslContext.fetchSingle(OBSERVATION_PLOTS)

    store.updateObservationPlotDetails(observationId, monitoringPlotId) {
      it.copy(notes = "New notes")
    }

    val expected = before.copy().apply { notes = "New notes" }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        ObservationPlotEditedEvent(
            changedFrom = ObservationPlotEditedEventValues(notes = null),
            changedTo = ObservationPlotEditedEventValues(notes = "New notes"),
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
        )
    )
  }

  @Test
  fun `throws exception if plot is not completed yet`() {
    val observationId = insertObservation()
    val monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot()

    assertThrows<PlotNotCompletedException> {
      store.updateObservationPlotDetails(observationId, monitoringPlotId) { it }
    }
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    val observationId = insertObservation()
    val monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot()

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateObservationPlotDetails(observationId, monitoringPlotId) { it }
    }
  }
}
