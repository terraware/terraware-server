package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
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
    val startDate: LocalDate,
    val status: PlantingSeasonStatus,
)
