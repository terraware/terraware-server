package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import kotlin.math.roundToInt

data class PlantingSiteReportedPlantTotals(
    val id: PlantingSiteId,
    val plantingZones: List<PlantingZone>,
    val plantsSinceLastObservation: Int,
    val species: List<Species>,
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
      val id: StratumId,
      val plantsSinceLastObservation: Int,
      val plantingSubzones: List<PlantingSubzone>,
      val species: List<Species>,
      val targetPlants: Int,
      val totalPlants: Int,
      val totalSpecies: Int,
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

  data class PlantingSubzone(
      val id: SubstratumId,
      val plantsSinceLastObservation: Int,
      val species: List<Species>,
      val totalPlants: Int,
      val totalSpecies: Int,
  )

  data class Species(
      val id: SpeciesId,
      val plantsSinceLastObservation: Int,
      val totalPlants: Int,
  )
}
