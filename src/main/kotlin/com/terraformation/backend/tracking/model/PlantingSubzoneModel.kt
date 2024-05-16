package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.time.Instant
import org.locationtech.jts.geom.MultiPolygon

data class PlantingSubzoneModel<PSZID : PlantingSubzoneId?>(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: PSZID,
    val fullName: String,
    val name: String,
    val plantingCompletedTime: Instant? = null,
    val monitoringPlots: List<MonitoringPlotModel> = emptyList(),
) {
  fun findMonitoringPlot(monitoringPlotId: MonitoringPlotId): MonitoringPlotModel? =
      monitoringPlots.find { it.id == monitoringPlotId }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSubzoneModel<*> &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        plantingCompletedTime == other.plantingCompletedTime &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        monitoringPlots.zip(other.monitoringPlots).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }

  fun toNew(): NewPlantingSubzoneModel =
      create(boundary, fullName, name, areaHa, plantingCompletedTime, monitoringPlots)

  companion object {
    fun create(
        boundary: MultiPolygon,
        fullName: String,
        name: String,
        areaHa: BigDecimal = boundary.calculateAreaHectares(),
        plantingCompletedTime: Instant? = null,
        monitoringPlots: List<MonitoringPlotModel> = emptyList(),
    ): NewPlantingSubzoneModel {
      return NewPlantingSubzoneModel(
          areaHa = areaHa,
          boundary = boundary,
          fullName = fullName,
          id = null,
          monitoringPlots = monitoringPlots,
          name = name,
          plantingCompletedTime = plantingCompletedTime,
      )
    }
  }
}

typealias ExistingPlantingSubzoneModel = PlantingSubzoneModel<PlantingSubzoneId>

typealias NewPlantingSubzoneModel = PlantingSubzoneModel<Nothing?>
