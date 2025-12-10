package com.terraformation.backend.tracking.model

import com.terraformation.backend.api.GpxWaypoint
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.i18n.Messages
import java.math.BigDecimal
import org.locationtech.jts.geom.Geometry

/**
 * Observation plot model with additional details needed by the "list assigned plots" API endpoint.
 */
data class AssignedPlotDetails(
    val model: ObservationPlotModel,
    val boundary: Geometry,
    val claimedByName: String?,
    val completedByName: String?,
    val elevationMeters: BigDecimal?,
    val isFirstObservation: Boolean,
    /** Null if the monitoring plot no longer falls within a subzone (due to a map edit). */
    val plantingSubzoneId: SubstratumId?,
    val plantingSubzoneName: String,
    val plantingZoneName: String,
    val plotNumber: Long,
    val sizeMeters: Int,
) {
  fun gpxWaypoints(messages: Messages): List<GpxWaypoint> {
    val plotType =
        if (model.isPermanent) messages.monitoringPlotTypePermanent()
        else messages.monitoringPlotTypeTemporary()

    return listOf(
        GpxWaypoint(
            boundary.coordinates[SOUTHWEST].y,
            boundary.coordinates[SOUTHWEST].x,
            messages.monitoringPlotSouthwestCorner(plotNumber),
            messages.monitoringPlotDescription(plotType, plantingZoneName, plantingSubzoneName),
            plotType,
        ),
        GpxWaypoint(
            boundary.coordinates[SOUTHEAST].y,
            boundary.coordinates[SOUTHEAST].x,
            messages.monitoringPlotSoutheastCorner(plotNumber),
            messages.monitoringPlotDescription(plotType, plantingZoneName, plantingSubzoneName),
            plotType,
        ),
        GpxWaypoint(
            boundary.coordinates[NORTHEAST].y,
            boundary.coordinates[NORTHEAST].x,
            messages.monitoringPlotNortheastCorner(plotNumber),
            messages.monitoringPlotDescription(plotType, plantingZoneName, plantingSubzoneName),
            plotType,
        ),
        GpxWaypoint(
            boundary.coordinates[NORTHWEST].y,
            boundary.coordinates[NORTHWEST].x,
            messages.monitoringPlotNorthwestCorner(plotNumber),
            messages.monitoringPlotDescription(plotType, plantingZoneName, plantingSubzoneName),
            plotType,
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
