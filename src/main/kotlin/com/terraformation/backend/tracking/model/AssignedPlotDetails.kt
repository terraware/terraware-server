package com.terraformation.backend.tracking.model

import com.terraformation.backend.api.GpxWaypoint
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.i18n.Messages
import org.locationtech.jts.geom.Geometry

/**
 * Observation plot model with additional details needed by the "list assigned plots" API endpoint.
 */
data class AssignedPlotDetails(
    val model: ObservationPlotModel,
    val boundary: Geometry,
    val claimedByName: String?,
    val completedByName: String?,
    val isFirstObservation: Boolean,
    val plantingSubzoneId: PlantingSubzoneId,
    val plantingSubzoneName: String,
    val plotNumber: Long,
    val sizeMeters: Int,
) {
  fun gpxWaypoints(messages: Messages): List<GpxWaypoint> {
    return listOf(
        GpxWaypoint(
            boundary.coordinates[SOUTHWEST].y,
            boundary.coordinates[SOUTHWEST].x,
            messages.monitoringPlotSouthwestCorner(plotNumber),
        ),
        GpxWaypoint(
            boundary.coordinates[SOUTHEAST].y,
            boundary.coordinates[SOUTHEAST].x,
            messages.monitoringPlotSoutheastCorner(plotNumber),
        ),
        GpxWaypoint(
            boundary.coordinates[NORTHEAST].y,
            boundary.coordinates[NORTHEAST].x,
            messages.monitoringPlotNortheastCorner(plotNumber),
        ),
        GpxWaypoint(
            boundary.coordinates[NORTHWEST].y,
            boundary.coordinates[NORTHWEST].x,
            messages.monitoringPlotNorthwestCorner(plotNumber),
        ),
    )
  }

  companion object {
    // 0 Index vertices for monitoring plot corners.
    const val SOUTHWEST = 0
    const val SOUTHEAST = 1
    const val NORTHEAST = 2
    const val NORTHWEST = 3
  }
}
