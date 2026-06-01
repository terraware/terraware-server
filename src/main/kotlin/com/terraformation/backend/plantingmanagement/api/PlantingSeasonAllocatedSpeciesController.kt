package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonAllocatedSpeciesStore
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/planting-seasons/{plantingSeasonId}/allocated-species")
@RestController
@TrackingEndpoint
class PlantingSeasonAllocatedSpeciesController(
    private val plantingSeasonAllocatedSpeciesStore: PlantingSeasonAllocatedSpeciesStore,
) {

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary = "Creates or updates the allocated quantity of a species for a planting season."
  )
  @PutMapping
  fun upsertAllocatedSpecies(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @RequestBody @Valid payload: UpsertPlantingSeasonAllocatedSpeciesRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSeasonAllocatedSpeciesStore.upsert(
        plantingSeasonId,
        payload.speciesId,
        payload.quantity,
    )
    return SimpleSuccessResponsePayload()
  }
}

data class UpsertPlantingSeasonAllocatedSpeciesRequestPayload(
    val speciesId: SpeciesId,
    @field:Min(0) val quantity: Int,
)
