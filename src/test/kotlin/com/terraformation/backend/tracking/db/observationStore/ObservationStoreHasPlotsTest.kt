package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationStoreHasPlotsTest : BaseObservationStoreTest() {
  @Test
  fun `returns false if observation has no plots`() {
    val observationId = insertObservation()

    assertFalse(store.hasPlots(observationId))
  }

  @Test
  fun `returns true if observation has plots`() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    val observationId = insertObservation()
    insertObservationPlot()

    assertTrue(store.hasPlots(observationId))
  }

  @Test
  fun `throws exception if no permission`() {
    val observationId = insertObservation()

    every { user.canReadObservation(observationId) } returns false

    assertThrows<ObservationNotFoundException> { store.hasPlots(observationId) }
  }

  @Test
  fun `throws exception if observation does not exist`() {
    assertThrows<ObservationNotFoundException> { store.hasPlots(ObservationId(1)) }
  }
}
