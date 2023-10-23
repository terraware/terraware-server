package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationPlotPosition
import org.locationtech.jts.geom.Point

data class NewObservedPlotCoordinatesModel(
    val gpsCoordinates: Point,
    val position: ObservationPlotPosition,
)
