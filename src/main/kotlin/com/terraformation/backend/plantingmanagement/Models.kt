package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import java.time.LocalDate

data class PlantingSeasonModel<ID : PlantingSeasonId?>(
    val endDate: LocalDate,
    val id: ID,
    val name: String,
    val plantingSiteId: PlantingSiteId,
    val startDate: LocalDate,
)

typealias NewPlantingSeasonModel = PlantingSeasonModel<Nothing?>

typealias ExistingPlantingSeasonModel = PlantingSeasonModel<PlantingSeasonId>
