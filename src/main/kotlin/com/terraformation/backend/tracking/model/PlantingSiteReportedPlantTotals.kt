package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import kotlin.math.roundToInt

data class PlantingSiteReportedPlantTotals(
    val id: PlantingSiteId,
    val plantingZones: List<PlantingZone>,
    val plantsSinceLastObservation: Int,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  val progressPercent: Int?
    get() {
      return if (plantingZones.isNotEmpty()) {
        val targetPlants = plantingZones.sumOf { it.targetPlants }
        if (targetPlants > 0) {
          (totalPlants * 100.0 / targetPlants).roundToInt()
        } else {
          0
        }
      } else {
        null
      }
    }

  data class PlantingZone(
      val id: PlantingZoneId,
      val plantsSinceLastObservation: Int,
      val targetPlants: Int,
      val totalPlants: Int,
  ) {
    val progressPercent: Int
      get() {
        return if (targetPlants > 0) {
          (totalPlants * 100.0 / targetPlants).roundToInt()
        } else {
          0
        }
      }
  }
}
