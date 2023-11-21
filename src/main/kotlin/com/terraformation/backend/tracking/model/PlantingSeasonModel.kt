package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlantingSeasonId
import java.time.Instant
import java.time.LocalDate

data class ExistingPlantingSeasonModel(
    val endDate: LocalDate,
    val endTime: Instant,
    val id: PlantingSeasonId,
    val isActive: Boolean,
    val startDate: LocalDate,
    val startTime: Instant,
)
