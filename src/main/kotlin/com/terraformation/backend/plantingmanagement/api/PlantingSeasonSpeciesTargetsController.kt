package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonSpeciesTargetsStore
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/planting-seasons/{plantingSeasonId}/species-targets")
@RestController
@TrackingEndpoint
class PlantingSeasonSpeciesTargetsController(
    private val plantingSeasonSpeciesTargetsStore: PlantingSeasonSpeciesTargetsStore,
) {

  @ApiResponse200
  @Operation(summary = "Gets all species targets for a planting season.")
  @GetMapping
  fun getSpeciesTargets(
      @PathVariable plantingSeasonId: PlantingSeasonId,
  ): ListSpeciesTargetsResponsePayload {
    val targets = plantingSeasonSpeciesTargetsStore.fetchList(plantingSeasonId)
    return ListSpeciesTargetsResponsePayload(targets.map { SpeciesTargetPayload(it) })
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary =
          "Creates or updates the target quantity for a species in a substratum for a planting season."
  )
  @PutMapping
  fun upsertSpeciesTarget(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @RequestBody @Valid payload: UpsertPlantingSeasonSpeciesTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSeasonSpeciesTargetsStore.upsert(
        plantingSeasonId,
        payload.substratumId,
        payload.speciesId,
        payload.quantity,
    )
    return SimpleSuccessResponsePayload()
  }
}

data class UpsertPlantingSeasonSpeciesTargetRequestPayload(
    val substratumId: SubstratumId,
    val speciesId: SpeciesId,
    @field:Min(0) val quantity: Int,
)

data class ListSpeciesTargetsResponsePayload(val targets: List<SpeciesTargetPayload>) :
    SuccessResponsePayload

data class SpeciesTargetPayload(
    val quantity: Int,
    val speciesId: SpeciesId,
    val substratumId: SubstratumId,
) {
  constructor(
      model: PlantingSeasonSpeciesTargetModel
  ) : this(
      quantity = model.quantity,
      speciesId = model.speciesId,
      substratumId = model.substratumId,
  )
}
