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

data class UpdatedPlantingSeasonModel(
    val endDate: LocalDate,
    val id: PlantingSeasonId? = null,
    val startDate: LocalDate,
) {
  constructor(
      existingModel: ExistingPlantingSeasonModel
  ) : this(
      endDate = existingModel.endDate,
      id = existingModel.id,
      startDate = existingModel.startDate,
  )

  fun validate(currentDate: LocalDate) {
    if (endDate < startDate.plusDays(28)) {
      throw PlantingSeasonTooShortException(startDate, endDate)
    }

    if (endDate > startDate.plusYears(1)) {
      throw PlantingSeasonTooLongException(startDate, endDate)
    }

    if (startDate > currentDate.plusYears(1)) {
      throw PlantingSeasonTooFarInFutureException(startDate)
    }

    if (id == null && endDate < currentDate) {
      throw CannotCreatePastPlantingSeasonException(startDate, endDate)
    }
  }
}
