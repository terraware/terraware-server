package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.tracking.db.PlotAlreadyClaimedException
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotClaimedException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreReleasePlotTest : BaseObservationStoreTest() {
  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId

  @BeforeEach
  fun insertDetailedSiteAndObservation() {
    insertPlantingZone()
    insertPlantingSubzone()
    plotId = insertMonitoringPlot()
    observationId = insertObservation()
  }

  @Test
  fun `releases claim on plot`() {
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Claimed,
    )

    store.releasePlot(observationId, plotId)

    val row = observationPlotsDao.findAll().first()

    assertEquals(ObservationPlotStatus.Unclaimed, row.statusId, "Plot status")
    assertNull(row.claimedBy, "Claimed by")
    assertNull(row.claimedTime, "Claimed time")
  }

  @Test
  fun `throws exception if plot is not claimed`() {
    insertObservationPlot()

    assertThrows<PlotNotClaimedException> { store.releasePlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if plot is claimed by someone else`() {
    val otherUserId = insertUser()

    insertObservationPlot(
        claimedBy = otherUserId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Claimed,
    )

    assertThrows<PlotAlreadyClaimedException> { store.releasePlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if plot observation status is completed`() {
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Completed,
    )

    assertThrows<PlotAlreadyCompletedException> { store.releasePlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if plot observation status is not observed`() {
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.NotObserved,
    )

    assertThrows<PlotAlreadyCompletedException> { store.releasePlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    insertObservationPlot()

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.releasePlot(observationId, plotId) }
  }

  @Test
  fun `throws exception if monitoring plot not assigned to observation`() {
    assertThrows<PlotNotInObservationException> { store.releasePlot(observationId, plotId) }
  }
}
