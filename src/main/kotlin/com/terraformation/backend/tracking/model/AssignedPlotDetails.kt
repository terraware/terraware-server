package com.terraformation.backend.tracking.model

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
)
