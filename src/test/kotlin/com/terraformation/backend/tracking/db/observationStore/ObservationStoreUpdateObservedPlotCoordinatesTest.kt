package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.point
import com.terraformation.backend.tracking.event.ObservationPlotCoordinatesEditedEvent
import com.terraformation.backend.tracking.event.ObservationPlotCoordinatesEditedEventValues
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
        gpsCoordinates = point(0),
        position = ObservationPlotPosition.NortheastCorner,
    )
    insertObservedCoordinates(
        gpsCoordinates = point(1),
        position = ObservationPlotPosition.NorthwestCorner,
    )
    insertObservedCoordinates(
        gpsCoordinates = point(3),
        position = ObservationPlotPosition.SoutheastCorner,
    )

    store.updateObservedPlotCoordinates(
        inserted.observationId,
        inserted.monitoringPlotId,
        listOf(
            NewObservedPlotCoordinatesModel(
                gpsCoordinates = point(0),
                position = ObservationPlotPosition.NortheastCorner,
            ),
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
            ObservationPlotPosition.NortheastCorner to point(0),
            ObservationPlotPosition.SouthwestCorner to point(1, 2),
            ObservationPlotPosition.SoutheastCorner to point(2, 2),
        ),
        observedPlotCoordinatesDao.findAll().associate { it.positionId!! to it.gpsCoordinates!! },
        "Coordinates after update",
    )

    // Northeast corner coordinates are unchanged, so there's no edit event for them.
    eventPublisher.assertEventsPublished(
        setOf(
            ObservationPlotCoordinatesEditedEvent(
                changedFrom = ObservationPlotCoordinatesEditedEventValues(point(1)),
                changedTo = ObservationPlotCoordinatesEditedEventValues(null),
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.NorthwestCorner,
            ),
            ObservationPlotCoordinatesEditedEvent(
                changedFrom = ObservationPlotCoordinatesEditedEventValues(point(3)),
                changedTo = ObservationPlotCoordinatesEditedEventValues(point(2)),
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.SoutheastCorner,
            ),
            ObservationPlotCoordinatesEditedEvent(
                changedFrom = ObservationPlotCoordinatesEditedEventValues(null),
                changedTo = ObservationPlotCoordinatesEditedEventValues(point(1, 2)),
                monitoringPlotId = inserted.monitoringPlotId,
                observationId = inserted.observationId,
                organizationId = inserted.organizationId,
                plantingSiteId = inserted.plantingSiteId,
                position = ObservationPlotPosition.SouthwestCorner,
            ),
        )
    )
  }
}
