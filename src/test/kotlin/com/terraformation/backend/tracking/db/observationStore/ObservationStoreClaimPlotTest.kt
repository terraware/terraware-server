package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.tracking.db.PlotAlreadyClaimedException
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreClaimPlotTest : BaseObservationStoreTest() {
  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId

  @BeforeEach
  fun insertDetailedSiteAndObservation() {
    insertStratum()
    insertSubstratum()
    plotId = insertMonitoringPlot()
    observationId = insertObservation()
  }

  @Test
  fun `claims plot if not claimed by anyone`() {
    insertObservationPlot()

    store.claimPlot(observationId, plotId)

    val row = observationPlotsDao.findAll().first()

    assertEquals(ObservationPlotStatus.Claimed, row.statusId, "Plot status")
    assertEquals(user.userId, row.claimedBy, "Claimed by")
    assertEquals(clock.instant, row.claimedTime, "Claimed time")
  }

  @Test
  fun `updates claim time if plot is reclaimed by current claimant`() {
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Claimed,
    )

    clock.instant = Instant.ofEpochSecond(2)

    store.claimPlot(observationId, plotId)

    val plotsRow = observationPlotsDao.findAll().first()

    assertEquals(ObservationPlotStatus.Claimed, plotsRow.statusId, "Plot status is unchanged")
    assertEquals(user.userId, plotsRow.claimedBy, "Should remain claimed by user")
    assertEquals(clock.instant, plotsRow.claimedTime, "Claim time should be updated")
  }

  @Test
  fun `throws exception if plot is claimed by someone else`() {
    val otherUserId = insertUser()

    insertObservationPlot(
        claimedBy = otherUserId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Claimed,
    )

    assertThrows<PlotAlreadyClaimedException> { store.claimPlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if plot observation status is completed`() {
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Completed,
    )

    assertThrows<PlotAlreadyCompletedException> { store.claimPlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if plot observation status is not observed`() {
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.NotObserved,
    )

    assertThrows<PlotAlreadyCompletedException> { store.claimPlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    insertObservationPlot()

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.claimPlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if monitoring plot not assigned to observation`() {
    assertThrows<PlotNotInObservationException> { store.claimPlot(observationId, plotId) }
  }
}
