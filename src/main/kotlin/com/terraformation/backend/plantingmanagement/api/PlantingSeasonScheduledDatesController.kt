package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateSpecies
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonScheduledDatesStore
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.time.LocalDate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/planting-seasons/{plantingSeasonId}/scheduled-dates")
@RestController
@TrackingEndpoint
class PlantingSeasonScheduledDatesController(
    private val plantingSeasonScheduledDatesStore: PlantingSeasonScheduledDatesStore,
) {
  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary = "Creates a new scheduled date for a planting season.",
  )
  @PostMapping
  fun createScheduledPlantingDate(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @RequestBody @Valid payload: ScheduledPlantingDateRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSeasonScheduledDatesStore.create(payload.toModel(plantingSeasonId))

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary = "Updates a scheduled date for a planting season.",
  )
  @PutMapping("/{id}")
  fun updateScheduledPlantingDate(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @PathVariable id: ScheduledPlantingDateId,
      @RequestBody @Valid payload: ScheduledPlantingDateRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSeasonScheduledDatesStore.update(id, payload.toModel(plantingSeasonId))

    return SimpleSuccessResponsePayload()
  }
}

data class ScheduledPlantingDateSpeciesPayload(
    @field:Min(0) val quantity: Int,
    val speciesId: SpeciesId,
    val substratumId: SubstratumId,
) {
  fun toModel(): PlantingSeasonScheduledDateSpecies =
      PlantingSeasonScheduledDateSpecies(
          quantity = quantity,
          speciesId = speciesId,
          substratumId = substratumId,
      )
}

data class ScheduledPlantingDateRequestPayload(
    val date: LocalDate,
    val species: List<ScheduledPlantingDateSpeciesPayload>,
) {
  fun toModel(plantingSeasonId: PlantingSeasonId): PlantingSeasonScheduledDateModel =
      PlantingSeasonScheduledDateModel(
          plantingSeasonId = plantingSeasonId,
          date = date,
          species = species.map { it.toModel() },
      )
}
