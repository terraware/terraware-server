package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.db.PlantingSiteSpeciesTargetStore
import com.terraformation.backend.tracking.model.PlantingSiteSpeciesTargetModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/sites/{plantingSiteId}/speciesTargets")
@RestController
@TrackingEndpoint
class PlantingSiteSpeciesTargetsController(
    private val plantingSiteSpeciesTargetStore: PlantingSiteSpeciesTargetStore,
) {
  @ApiResponse200
  @ApiResponse404("The planting site does not exist.")
  @GetMapping
  @Operation(summary = "Gets the species targeted for planting at a planting site.")
  fun listPlantingSiteSpeciesTargets(
      @PathVariable plantingSiteId: PlantingSiteId,
  ): ListPlantingSiteSpeciesTargetsResponsePayload {
    val models = plantingSiteSpeciesTargetStore.fetchByPlantingSiteId(plantingSiteId)

    return ListPlantingSiteSpeciesTargetsResponsePayload(
        models.map { PlantingSiteSpeciesTargetPayload(it) }
    )
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404("The planting site, one of the strata, or the species does not exist.")
  @ApiResponse409("The species is in a different organization than the planting site.")
  @Operation(
      summary = "Targets a species for planting at a planting site.",
      description =
          "If the species is already targeted at the site, its target number of plants and its " +
              "list of strata are replaced with the values from the request.",
  )
  @PutMapping("/{speciesId}")
  fun updatePlantingSiteSpeciesTarget(
      @PathVariable plantingSiteId: PlantingSiteId,
      @PathVariable speciesId: SpeciesId,
      @RequestBody @Valid payload: UpdatePlantingSiteSpeciesTargetRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSiteSpeciesTargetStore.upsert(plantingSiteId, payload.toModel(speciesId))

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404("The planting site does not exist.")
  @DeleteMapping("/{speciesId}")
  @Operation(
      summary = "Stops targeting a species for planting at a planting site.",
      description =
          "The species is also removed from the strata where it was targeted. Succeeds even if " +
              "the species wasn't targeted at the site.",
  )
  fun deletePlantingSiteSpeciesTarget(
      @PathVariable plantingSiteId: PlantingSiteId,
      @PathVariable speciesId: SpeciesId,
  ): SimpleSuccessResponsePayload {
    plantingSiteSpeciesTargetStore.delete(plantingSiteId, speciesId)

    return SimpleSuccessResponsePayload()
  }
}

data class PlantingSiteSpeciesTargetPayload(
    val speciesId: SpeciesId,
    @Schema(description = "Strata of the planting site where the species is targeted for planting.")
    val stratumIds: Set<StratumId>,
    @Schema(description = "Number of plants targeted for the planting site as a whole.")
    val targetPlants: Long?,
) {
  constructor(
      model: PlantingSiteSpeciesTargetModel
  ) : this(
      speciesId = model.speciesId,
      stratumIds = model.stratumIds,
      targetPlants = model.targetPlants,
  )
}

data class UpdatePlantingSiteSpeciesTargetRequestPayload(
    @Schema(
        description =
            "Strata of the planting site where the species is targeted for planting. There is no " +
                "target number of plants at the stratum level."
    )
    val stratumIds: Set<StratumId> = emptySet(),
    @Min(0)
    @Schema(description = "Number of plants targeted for the planting site as a whole.")
    val targetPlants: Long? = null,
) {
  fun toModel(speciesId: SpeciesId) =
      PlantingSiteSpeciesTargetModel(
          speciesId = speciesId,
          stratumIds = stratumIds,
          targetPlants = targetPlants,
      )
}

data class ListPlantingSiteSpeciesTargetsResponsePayload(
    val targets: List<PlantingSiteSpeciesTargetPayload>
) : SuccessResponsePayload
