package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.ObservationPlotNotFoundException
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ObservationPlotModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationStoreFetchOneObservationPlotDetailsTest : BaseObservationStoreTest() {
  @Test
  fun `calculates correct values from related tables`() {
    val userId1 = insertUser(firstName = "First", lastName = "Person")
    val userId2 = insertUser(firstName = "Second", lastName = "Human")

    insertStratum(name = "Z1")
    val substratumId1 = insertSubstratum(fullName = "Z1-S1", name = "S1")

    // A plot that was observed previously and again in this observation
    val monitoringPlotId11 =
        insertMonitoringPlot(boundary = polygon(1), elevationMeters = BigDecimal.TEN)
    insertObservation()
    insertObservationPlot()
    val observationId = insertObservation()
    insertObservationPlot(isPermanent = true)

    // This plot is claimed
    val monitoringPlotId12 = insertMonitoringPlot(boundary = polygon(2))
    val claimedTime12 = Instant.ofEpochSecond(12)
    insertObservationPlot(
        ObservationPlotsRow(
            claimedBy = userId1,
            claimedTime = claimedTime12,
            statusId = ObservationPlotStatus.Claimed,
        )
    )

    val substratumId2 = insertSubstratum(fullName = "Z1-S2", name = "S2")

    // This plot is claimed and completed
    val monitoringPlotId21 = insertMonitoringPlot(boundary = polygon(3))
    val claimedTime21 = Instant.ofEpochSecond(210)
    val completedTime21 = Instant.ofEpochSecond(211)
    val observedTime21 = Instant.ofEpochSecond(212)
    insertObservationPlot(
        ObservationPlotsRow(
            claimedBy = userId2,
            claimedTime = claimedTime21,
            completedBy = userId1,
            completedTime = completedTime21,
            notes = "Some notes",
            observedTime = observedTime21,
            statusId = ObservationPlotStatus.Completed,
        )
    )

    assertEquals(
        AssignedPlotDetails(
            model =
                ObservationPlotModel(
                    isPermanent = true,
                    monitoringPlotId = monitoringPlotId11,
                    observationId = observationId,
                ),
            boundary = polygon(1),
            claimedByName = null,
            completedByName = null,
            elevationMeters = BigDecimal.TEN,
            isFirstObservation = false,
            substratumId = substratumId1,
            substratumName = "Z1-S1",
            stratumName = "Z1",
            plotNumber = 1,
            sizeMeters = 30,
        ),
        store.fetchOneObservationPlotDetails(observationId, monitoringPlotId11),
        "Plot 11",
    )

    assertEquals(
        AssignedPlotDetails(
            model =
                ObservationPlotModel(
                    claimedBy = userId1,
                    claimedTime = claimedTime12,
                    isPermanent = false,
                    monitoringPlotId = monitoringPlotId12,
                    observationId = observationId,
                ),
            boundary = polygon(2),
            claimedByName = "First Person",
            completedByName = null,
            elevationMeters = null,
            isFirstObservation = true,
            substratumId = substratumId1,
            substratumName = "Z1-S1",
            stratumName = "Z1",
            plotNumber = 2,
            sizeMeters = 30,
        ),
        store.fetchOneObservationPlotDetails(observationId, monitoringPlotId12),
        "Plot 12",
    )

    assertEquals(
        AssignedPlotDetails(
            model =
                ObservationPlotModel(
                    claimedBy = userId2,
                    claimedTime = claimedTime21,
                    completedBy = userId1,
                    completedTime = completedTime21,
                    isPermanent = false,
                    monitoringPlotId = monitoringPlotId21,
                    notes = "Some notes",
                    observationId = observationId,
                    observedTime = observedTime21,
                ),
            boundary = polygon(3),
            claimedByName = "Second Human",
            completedByName = "First Person",
            elevationMeters = null,
            isFirstObservation = true,
            substratumId = substratumId2,
            substratumName = "Z1-S2",
            stratumName = "Z1",
            plotNumber = 3,
            sizeMeters = 30,
        ),
        store.fetchOneObservationPlotDetails(observationId, monitoringPlotId21),
        "Plot 21",
    )
  }

  @Test
  fun `throws exception if no permission to read observation`() {
    every { user.canReadObservation(any()) } returns false

    val observationId = insertObservation()
    val monitoringPlotId11 = insertMonitoringPlot(boundary = polygon(1))
    insertObservationPlot()

    assertThrows<ObservationNotFoundException> {
      store.fetchOneObservationPlotDetails(observationId, monitoringPlotId11)
    }
  }

  @Test
  fun `throws exception if plot is not assigned to observation`() {
    val observationId = insertObservation()
    val monitoringPlotId11 = insertMonitoringPlot(boundary = polygon(1))

    assertThrows<ObservationPlotNotFoundException> {
      store.fetchOneObservationPlotDetails(observationId, monitoringPlotId11)
    }
  }
}
