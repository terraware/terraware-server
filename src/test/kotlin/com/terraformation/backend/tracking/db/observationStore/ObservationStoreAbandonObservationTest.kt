package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.tracking.db.ObservationAlreadyEndedException
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.security.access.AccessDeniedException

class ObservationStoreAbandonObservationTest : BaseObservationStoreTest() {
  @Test
  fun `deletes an observation if no plot has been completed`() {
    val observationId = insertObservation()
    assertNotNull(observationsDao.fetchOneById(observationId), "Before abandon")
    store.abandonObservation(observationId)
    assertNull(observationsDao.fetchOneById(observationId), "After abandon")
  }

  @Test
  fun `sets an observation to Abandoned and incomplete plots to Not Observed and unclaims`() {
    insertStratum()
    insertSubstratum()
    val earlierCompletedPlotId = insertMonitoringPlot()
    val laterCompletedPlotId = insertMonitoringPlot()
    val unclaimedPlotId = insertMonitoringPlot()
    val claimedPlotId = insertMonitoringPlot()

    val observationId = insertObservation()

    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = earlierCompletedPlotId,
        claimedBy = currentUser().userId,
        claimedTime = Instant.EPOCH,
        completedBy = currentUser().userId,
        completedTime = Instant.ofEpochSecond(6000),
        statusId = ObservationPlotStatus.Completed,
    )

    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = laterCompletedPlotId,
        claimedBy = currentUser().userId,
        claimedTime = Instant.EPOCH,
        completedBy = currentUser().userId,
        completedTime = Instant.ofEpochSecond(12000),
        statusId = ObservationPlotStatus.Completed,
    )

    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = unclaimedPlotId,
        statusId = ObservationPlotStatus.Unclaimed,
    )

    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = claimedPlotId,
        claimedBy = currentUser().userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Claimed,
    )

    val existing = observationsDao.fetchOneById(observationId)!!

    val plotsRows = observationPlotsDao.findAll().associateBy { it.monitoringPlotId }
    val earlierCompletedRow = plotsRows[earlierCompletedPlotId]!!
    val laterCompletedRow = plotsRows[laterCompletedPlotId]!!
    val unclaimedRow = plotsRows[unclaimedPlotId]!!
    val claimedRow = plotsRows[claimedPlotId]!!

    clock.instant = Instant.ofEpochSecond(500)

    store.abandonObservation(observationId)

    assertSetEquals(
        setOf(
            earlierCompletedRow,
            laterCompletedRow,
            unclaimedRow.copy(statusId = ObservationPlotStatus.NotObserved),
            claimedRow.copy(
                claimedBy = null,
                claimedTime = null,
                statusId = ObservationPlotStatus.NotObserved,
            ),
        ),
        observationPlotsDao.fetchByObservationId(observationId).toSet(),
        "Observation plots after abandoning",
    )

    assertEquals(
        existing.copy(
            completedTime = Instant.ofEpochSecond(12000),
            stateId = ObservationState.Abandoned,
        ),
        observationsDao.fetchOneById(observationId),
        "Observation after abandoning",
    )
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    val observationId = insertObservation()

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.abandonObservation(observationId) }
  }

  @EnumSource(names = ["Abandoned", "Completed"])
  @ParameterizedTest
  fun `throws exception when abandoning an already ended observation`(state: ObservationState) {
    val observationId = insertObservation(completedTime = Instant.EPOCH, state = state)

    insertStratum()
    insertSubstratum()
    val completedPlot = insertMonitoringPlot()

    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = completedPlot,
        claimedBy = currentUser().userId,
        claimedTime = Instant.EPOCH,
        completedBy = currentUser().userId,
        completedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Completed,
    )

    assertThrows<ObservationAlreadyEndedException> { store.abandonObservation(observationId) }
  }
}
