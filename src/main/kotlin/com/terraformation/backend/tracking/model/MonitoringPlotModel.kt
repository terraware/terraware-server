package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import java.math.BigDecimal
import org.locationtech.jts.geom.Polygon

data class MonitoringPlotModel(
    val boundary: Polygon,
    val elevationMeters: BigDecimal?,
    val id: MonitoringPlotId,
    val isAdHoc: Boolean,
    val isAvailable: Boolean,
    val permanentIndex: Int? = null,
    val plotNumber: Long,
    val sizeMeters: Int,
) {
  val areaHa: Double
    get() = sizeMeters * sizeMeters / SQUARE_METERS_PER_HECTARE

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is MonitoringPlotModel &&
        elevationMeters == other.elevationMeters &&
        id == other.id &&
        isAdHoc == other.isAdHoc &&
        isAvailable == other.isAvailable &&
        permanentIndex == other.permanentIndex &&
        plotNumber == other.plotNumber &&
        sizeMeters == other.sizeMeters &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
