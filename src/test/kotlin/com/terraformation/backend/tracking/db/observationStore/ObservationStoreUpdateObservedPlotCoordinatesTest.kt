package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservationStoreUpdateObservedPlotCoordinatesTest : BaseObservationStoreTest() {
  @Test
  fun `can add and remove observed coordinates`() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    insertObservation()
    insertObservationPlot(completedBy = user.userId)
    insertObservedCoordinates(
        gpsCoordinates = point(1),
        position = ObservationPlotPosition.NorthwestCorner,
    )

    store.updateObservedPlotCoordinates(
        inserted.observationId,
        inserted.monitoringPlotId,
        listOf(
            NewObservedPlotCoordinatesModel(
                gpsCoordinates = point(2),
                position = ObservationPlotPosition.SoutheastCorner,
            ),
            NewObservedPlotCoordinatesModel(
                gpsCoordinates = point(1, 2),
                position = ObservationPlotPosition.SouthwestCorner,
            ),
        ),
    )

    assertEquals(
        mapOf(
            ObservationPlotPosition.SouthwestCorner to point(1, 2),
            ObservationPlotPosition.SoutheastCorner to point(2, 2),
        ),
        observedPlotCoordinatesDao.findAll().associate { it.positionId!! to it.gpsCoordinates!! },
        "Coordinates after update",
    )
  }
}
