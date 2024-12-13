package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import org.locationtech.jts.geom.Polygon

data class MonitoringPlotModel(
    val boundary: Polygon,
    val id: MonitoringPlotId,
    val isAdHoc: Boolean,
    val isAvailable: Boolean,
    val fullName: String,
    val name: String,
    val permanentCluster: Int? = null,
    val permanentClusterSubplot: Int? = null,
    val plotNumber: Long,
    val sizeMeters: Int,
) {
  val areaHa: Double
    get() = sizeMeters * sizeMeters / SQUARE_METERS_PER_HECTARE

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is MonitoringPlotModel &&
        id == other.id &&
        isAdHoc == other.isAdHoc &&
        isAvailable == other.isAvailable &&
        fullName == other.fullName &&
        name == other.name &&
        permanentCluster == other.permanentCluster &&
        permanentClusterSubplot == other.permanentClusterSubplot &&
        plotNumber == other.plotNumber &&
        sizeMeters == other.sizeMeters &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
