package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

data class PlantingSubzoneModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: PlantingSubzoneId,
    val fullName: String,
    val name: String,
    val monitoringPlots: List<MonitoringPlotModel>,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSubzoneModel &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        monitoringPlots.zip(other.monitoringPlots).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
