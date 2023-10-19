package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import org.locationtech.jts.geom.Polygon

data class MonitoringPlotModel(
    val boundary: Polygon,
    val id: MonitoringPlotId,
    val isAvailable: Boolean,
    val fullName: String,
    val name: String,
    val permanentCluster: Int? = null,
    val permanentClusterSubplot: Int? = null,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is MonitoringPlotModel &&
        id == other.id &&
        isAvailable == other.isAvailable &&
        fullName == other.fullName &&
        name == other.name &&
        permanentCluster == other.permanentCluster &&
        permanentClusterSubplot == other.permanentClusterSubplot &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
