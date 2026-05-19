package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonSpeciesTargetsStore
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/planting-seasons/species-targets")
@RestController
@TrackingEndpoint
class PlantingSeasonSpeciesTargetsController(
    private val plantingSeasonSpeciesTargetsStore: PlantingSeasonSpeciesTargetsStore,
) {
  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary =
          "Creates or updates the target quantity for a species in a substratum for a planting season."
  )
  @PostMapping
  fun upsertSpeciesTarget(
      @RequestBody @Valid payload: UpsertPlantingSeasonSpeciesTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSeasonSpeciesTargetsStore.upsert(
        payload.plantingSeasonId,
        payload.substratumId,
        payload.speciesId,
        payload.quantity,
    )
    return SimpleSuccessResponsePayload()
  }
}

data class UpsertPlantingSeasonSpeciesTargetRequestPayload(
    val plantingSeasonId: PlantingSeasonId,
    val substratumId: SubstratumId,
    val speciesId: SpeciesId,
    @field:Min(0) val quantity: Int,
)
