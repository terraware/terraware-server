package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.tracking.event.ObservationPlotCreatedEvent
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

class ObservationStoreAddPlotsToObservationTest : BaseObservationStoreTest() {
  @Test
  fun `honors isPermanent flag`() {
    insertStratum()
    insertSubstratum()
    val permanentPlotId = insertMonitoringPlot(permanentIndex = 1)
    val permanentPlotHistoryId = inserted.monitoringPlotHistoryId
    val temporaryPlotId = insertMonitoringPlot(permanentIndex = 2)
    val temporaryPlotHistoryId = inserted.monitoringPlotHistoryId
    val observationId = insertObservation()

    store.addPlotsToObservation(observationId, listOf(permanentPlotId), isPermanent = true)
    store.addPlotsToObservation(observationId, listOf(temporaryPlotId), isPermanent = false)

    assertEquals(
        mapOf(permanentPlotId to true, temporaryPlotId to false),
        observationPlotsDao.findAll().associate { it.monitoringPlotId to it.isPermanent },
    )

    eventPublisher.assertEventsPublished(
        setOf(
            ObservationPlotCreatedEvent(
                isPermanent = true,
                monitoringPlotHistoryId = permanentPlotHistoryId,
                monitoringPlotId = permanentPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                plotNumber = 1,
            ),
            ObservationPlotCreatedEvent(
                isPermanent = false,
                monitoringPlotHistoryId = temporaryPlotHistoryId,
                monitoringPlotId = temporaryPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                plotNumber = 2,
            ),
        )
    )
  }

  @Test
  fun `throws exception if same plot is added twice`() {
    insertStratum()
    insertSubstratum()
    val plotId = insertMonitoringPlot()
    val observationId = insertObservation()

    store.addPlotsToObservation(observationId, listOf(plotId), true)

    assertThrows<DuplicateKeyException> {
      store.addPlotsToObservation(observationId, listOf(plotId), false)
    }
  }

  @Test
  fun `throws exception if plots belong to a different planting site`() {
    val observationId = insertObservation()

    insertPlantingSite()
    insertStratum()
    insertSubstratum()
    val otherSitePlotId = insertMonitoringPlot()

    assertThrows<IllegalStateException> {
      store.addPlotsToObservation(observationId, listOf(otherSitePlotId), true)
    }
  }

  @Test
  fun `throws exception for an ad-hoc observation`() {
    val observationId = insertObservation(isAdHoc = true)

    insertStratum()
    insertSubstratum()
    val plotId = insertMonitoringPlot()

    assertThrows<IllegalStateException> {
      store.addPlotsToObservation(observationId, listOf(plotId), true)
    }
  }

  @Test
  fun `throws exception for an an-hoc plot`() {
    val observationId = insertObservation()

    insertStratum()
    insertSubstratum()
    val plotId = insertMonitoringPlot(isAdHoc = true)

    assertThrows<IllegalStateException> {
      store.addPlotsToObservation(observationId, listOf(plotId), true)
    }
  }

  @Test
  fun `throws exception if no permission to manage observation`() {
    val observationId = insertObservation()

    every { user.canManageObservation(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.addPlotsToObservation(observationId, emptyList(), true)
    }
  }
}
