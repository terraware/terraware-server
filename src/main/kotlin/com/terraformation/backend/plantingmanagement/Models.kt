package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import java.time.LocalDate

data class NewPlantingSeasonModel(
    val endDate: LocalDate,
    val fromPlantingSeasonId: PlantingSeasonId? = null,
    val name: String,
    val plantingSiteId: PlantingSiteId,
    val startDate: LocalDate,
)

data class ExistingPlantingSeasonModel(
    val endDate: LocalDate,
    val id: PlantingSeasonId,
    val name: String,
    val plantingSiteId: PlantingSiteId,
    val speciesTargets: List<PlantingSeasonSpeciesTargetModel> = emptyList(),
    val startDate: LocalDate,
    val status: PlantingSeasonStatus,
)

data class PlantingSeasonSpeciesTargetModel(
    val quantity: Int,
    val speciesId: SpeciesId,
    val substratumId: SubstratumId,
)

data class PlantingSeasonScheduledDateSpecies(
    val quantity: Int,
    val speciesId: SpeciesId,
    val substratumId: SubstratumId,
)

data class PlantingSeasonScheduledDateModel(
    val date: LocalDate,
    val plantingSeasonId: PlantingSeasonId,
    val species: List<PlantingSeasonScheduledDateSpecies> = emptyList(),
) {
  init {
    require(species.all { it.quantity >= 0 }) { "All quantities must be >= 0" }
    require(species.size == species.distinctBy { it.substratumId to it.speciesId }.size) {
      "Species listed multiple times for substratum"
    }
  }
}

data class ExistingPlantingSeasonScheduledDateModel(
    val date: LocalDate,
    val plantingSeasonId: PlantingSeasonId,
    val scheduledPlantingDateId: ScheduledPlantingDateId,
    val species: List<PlantingSeasonScheduledDateSpecies> = emptyList(),
)
