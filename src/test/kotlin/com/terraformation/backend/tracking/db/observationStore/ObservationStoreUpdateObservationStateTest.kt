package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.tracking.event.ObservationStateUpdatedEvent
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreUpdateObservationStateTest : BaseObservationStoreTest() {
  @Test
  fun `updates state from InProgress to Completed if user has update permission`() {
    val observationId = insertObservation()
    val initial = store.fetchObservationById(observationId)

    every { user.canManageObservation(observationId) } returns false

    // at least one completed plot is required for Completed state.
    val plotId = insertMonitoringPlot()
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = plotId,
        claimedBy = currentUser().userId,
        claimedTime = Instant.EPOCH,
        completedBy = currentUser().userId,
        completedTime = Instant.ofEpochSecond(6000),
        statusId = ObservationPlotStatus.Completed,
    )

    store.updateObservationState(observationId, ObservationState.Completed)

    assertEquals(
        initial.copy(
            completedTime = Instant.ofEpochSecond(6000),
            state = ObservationState.Completed,
        ),
        store.fetchObservationById(observationId),
    )
    eventPublisher.assertEventPublished(
        ObservationStateUpdatedEvent(observationId, ObservationState.Completed)
    )
  }

  @Test
  fun `throws exception if observation has no completed plot when updating to Completed`() {
    val observationId = insertObservation()

    every { user.canManageObservation(observationId) } returns false

    val plotId = insertMonitoringPlot()
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = plotId,
        claimedBy = currentUser().userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Claimed,
    )

    assertThrows<IllegalStateException> {
      store.updateObservationState(observationId, ObservationState.Completed)
    }
  }

  @Test
  fun `throws exception if no permission to update to Completed`() {
    val observationId = insertObservation()

    every { user.canManageObservation(observationId) } returns false
    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateObservationState(observationId, ObservationState.Completed)
    }
  }

  @Test
  fun `throws exception if setting state to InProgress`() {
    val observationId = insertObservation(state = ObservationState.Upcoming)

    assertThrows<IllegalArgumentException> {
      store.updateObservationState(observationId, ObservationState.InProgress)
    }
  }

  @Test
  fun `throws exception on illegal state transition`() {
    val observationId = insertObservation(state = ObservationState.InProgress)

    assertThrows<IllegalArgumentException> {
      store.updateObservationState(observationId, ObservationState.Upcoming)
    }
  }
}
