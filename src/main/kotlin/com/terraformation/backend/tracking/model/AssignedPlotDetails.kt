package com.terraformation.backend.tracking.model

import com.terraformation.backend.api.GpxWaypoint
import com.terraformation.backend.db.tracking.PlantingSubzoneId
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
    val plotName: String,
) {
  fun gpxWaypoints(): List<GpxWaypoint> {
    return listOf(
        GpxWaypoint(
            boundary.coordinates[SOUTHWEST].y,
            boundary.coordinates[SOUTHWEST].x,
            "$plotName Southwest Corner"),
        GpxWaypoint(
            boundary.coordinates[NORTHWEST].y,
            boundary.coordinates[NORTHWEST].x,
            "$plotName Northwest Corner"),
        GpxWaypoint(
            boundary.coordinates[NORTHEAST].y,
            boundary.coordinates[NORTHEAST].x,
            "$plotName Northeast Corner"),
        GpxWaypoint(
            boundary.coordinates[SOUTHEAST].y,
            boundary.coordinates[SOUTHEAST].x,
            "$plotName Southeast Corner"),
    )
  }

  companion object {
    // 0 Index vertices for monitoring plot corners.
    const val SOUTHWEST = 0
    const val NORTHWEST = 1
    const val NORTHEAST = 2
    const val SOUTHEAST = 3
  }
}
