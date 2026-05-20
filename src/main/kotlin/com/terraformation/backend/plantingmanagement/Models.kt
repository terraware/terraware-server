package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.SubstratumId
import java.time.LocalDate

data class NewPlantingSeasonModel(
    val endDate: LocalDate,
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
