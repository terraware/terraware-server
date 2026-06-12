package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreDeleteIncompletePlotsTest : BaseObservationStoreTest() {
  @Test
  fun `deletes incomplete plots from the observation`() {
    insertUserGlobalRole(role = GlobalRole.SuperAdmin)
    insertStratum()
    insertSubstratum()

    // A completed plot; it shouldn't be removed.
    insertMonitoringPlot()
    val observationId =
        insertObservation(completedTime = Instant.EPOCH, state = ObservationState.Abandoned)
    insertObservationPlot(completedBy = user.userId)

    val observationPlotsWithOnlyCompletedPlot = dslContext.fetch(OBSERVATION_PLOTS)

    // Claimed, not-observed, and unclaimed plots should be removed.
    insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId)
    insertMonitoringPlot()
    insertObservationPlot(statusId = ObservationPlotStatus.NotObserved)
    insertMonitoringPlot()
    insertObservationPlot()

    store.deleteIncompletePlots(observationId)

    assertTableEquals(observationPlotsWithOnlyCompletedPlot)
  }

  @Test
  fun `throws exception if observation is not abandoned`() {
    insertUserGlobalRole(role = GlobalRole.SuperAdmin)
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    val observationId = insertObservation()
    insertObservationPlot()

    assertThrows<IllegalStateException> { store.deleteIncompletePlots(observationId) }
  }

  @Test
  fun `throws exception if no permission to manage observation`() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    val observationId = insertObservation()
    insertObservationPlot()

    every { user.canManageObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.deleteIncompletePlots(observationId) }
  }
}
