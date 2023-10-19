package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.time.Instant
import org.locationtech.jts.geom.MultiPolygon

data class PlantingSubzoneModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: PlantingSubzoneId,
    val fullName: String,
    val name: String,
    val plantingCompletedTime: Instant?,
    val monitoringPlots: List<MonitoringPlotModel>,
) {
  fun chooseTemporaryPlots(
      excludePlotIds: Set<MonitoringPlotId>,
      count: Int
  ): List<MonitoringPlotId> {
    return monitoringPlots
        .asSequence()
        .filter { it.id !in excludePlotIds }
        .filter { it.isAvailable }
        .filter { it.boundary.coveredBy(boundary) }
        .map { it.id }
        .shuffled()
        .take(count)
        .toList()
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSubzoneModel &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        plantingCompletedTime == other.plantingCompletedTime &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        monitoringPlots.zip(other.monitoringPlots).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
