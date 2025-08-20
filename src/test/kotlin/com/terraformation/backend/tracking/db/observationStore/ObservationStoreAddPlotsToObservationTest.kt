package com.terraformation.backend.tracking.db.observationStore

import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

class ObservationStoreAddPlotsToObservationTest : BaseObservationStoreTest() {
  @Test
  fun `honors isPermanent flag`() {
    insertPlantingZone()
    insertPlantingSubzone()
    val permanentPlotId = insertMonitoringPlot(permanentIndex = 1)
    val temporaryPlotId = insertMonitoringPlot(permanentIndex = 2)
    val observationId = insertObservation()

    store.addPlotsToObservation(observationId, listOf(permanentPlotId), isPermanent = true)
    store.addPlotsToObservation(observationId, listOf(temporaryPlotId), isPermanent = false)

    assertEquals(
        mapOf(permanentPlotId to true, temporaryPlotId to false),
        observationPlotsDao.findAll().associate { it.monitoringPlotId to it.isPermanent },
    )
  }

  @Test
  fun `throws exception if same plot is added twice`() {
    insertPlantingZone()
    insertPlantingSubzone()
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
    insertPlantingZone()
    insertPlantingSubzone()
    val otherSitePlotId = insertMonitoringPlot()

    assertThrows<IllegalStateException> {
      store.addPlotsToObservation(observationId, listOf(otherSitePlotId), true)
    }
  }

  @Test
  fun `throws exception for an ad-hoc observation`() {
    val observationId = insertObservation(isAdHoc = true)

    insertPlantingZone()
    insertPlantingSubzone()
    val plotId = insertMonitoringPlot()

    assertThrows<IllegalStateException> {
      store.addPlotsToObservation(observationId, listOf(plotId), true)
    }
  }

  @Test
  fun `throws exception for an an-hoc plot`() {
    val observationId = insertObservation()

    insertPlantingZone()
    insertPlantingSubzone()
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
