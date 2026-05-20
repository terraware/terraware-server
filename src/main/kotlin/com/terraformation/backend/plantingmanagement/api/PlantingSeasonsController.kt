package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.NewPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonService
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonStore
import io.swagger.v3.oas.annotations.Operation
import java.time.LocalDate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@TrackingEndpoint
@RequestMapping("/api/v1/planting-seasons")
@RestController
class PlantingSeasonsController(
    private val plantingSeasonService: PlantingSeasonService,
    private val plantingSeasonStore: PlantingSeasonStore,
) {

  @ApiResponse200
  @Operation(
      summary = "Creates a new planting season.",
      description =
          "If fromPlantingSeasonId is specified, copy all species/substrata over to the new season (with quantities of 0).",
  )
  @PostMapping
  fun createPlantingSeason(
      @RequestBody payload: CreatePlantingSeasonRequestPayload
  ): CreatePlantingSeasonResponsePayload {
    payload.validate()

    val plantingSeasonId = plantingSeasonService.create(payload.toModel())
    return CreatePlantingSeasonResponsePayload(plantingSeasonId)
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(summary = "Gets information about a specific planting season.")
  @GetMapping("/{id}")
  fun getPlantingSeason(
      @PathVariable id: PlantingSeasonId,
  ): GetPlantingSeasonResponsePayload {
    val model = plantingSeasonStore.fetchById(id)
    return GetPlantingSeasonResponsePayload(model)
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(summary = "Updates an existing planting season.")
  @PutMapping("/{id}")
  fun updatePlantingSeason(
      @PathVariable id: PlantingSeasonId,
      @RequestBody payload: UpdatePlantingSeasonRequestPayload,
  ): SimpleSuccessResponsePayload {
    payload.validate()
    plantingSeasonStore.update(id, payload.name, payload.startDate, payload.endDate)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(summary = "Deletes a planting season.")
  @DeleteMapping("/{id}")
  fun deletePlantingSeason(
      @PathVariable id: PlantingSeasonId,
  ): SimpleSuccessResponsePayload {
    plantingSeasonStore.delete(id)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @Operation(summary = "Lists the planting seasons for a planting site.")
  @GetMapping
  fun listPlantingSeasons(
      @RequestParam plantingSiteId: PlantingSiteId,
  ): ListPlantingSeasonsResponsePayload {
    val seasons = plantingSeasonStore.fetchList(plantingSiteId)
    return ListPlantingSeasonsResponsePayload(seasons.map { PlantingSeasonPayload(it) })
  }
}

private fun validateDateRange(startDate: LocalDate, endDate: LocalDate) {
  if (startDate > endDate) {
    throw IllegalArgumentException("Start date must be before end date")
  }
}

data class CreatePlantingSeasonRequestPayload(
    val endDate: LocalDate,
    val fromPlantingSeasonId: PlantingSeasonId? = null,
    val name: String,
    val plantingSiteId: PlantingSiteId,
    val startDate: LocalDate,
) {
  fun validate() {
    validateDateRange(startDate, endDate)
  }

  fun toModel(): NewPlantingSeasonModel =
      NewPlantingSeasonModel(
          endDate = endDate,
          fromPlantingSeasonId = fromPlantingSeasonId,
          name = name,
          plantingSiteId = plantingSiteId,
          startDate = startDate,
      )
}

data class CreatePlantingSeasonResponsePayload(val id: PlantingSeasonId) : SuccessResponsePayload

data class UpdatePlantingSeasonRequestPayload(
    val endDate: LocalDate,
    val name: String,
    val startDate: LocalDate,
) {
  fun validate() {
    validateDateRange(startDate, endDate)
  }
}

data class GetPlantingSeasonResponsePayload(
    val season: PlantingSeasonPayload,
) : SuccessResponsePayload {
  constructor(model: ExistingPlantingSeasonModel) : this(PlantingSeasonPayload(model))
}

data class ListPlantingSeasonsResponsePayload(
    val seasons: List<PlantingSeasonPayload>,
) : SuccessResponsePayload

data class PlantingSeasonPayload(
    val endDate: LocalDate,
    val id: PlantingSeasonId,
    val name: String,
    val plantingSiteId: PlantingSiteId,
    val speciesTargets: List<SpeciesTargetPayload> = emptyList(),
    val startDate: LocalDate,
    val status: PlantingSeasonStatus,
) {
  constructor(
      model: ExistingPlantingSeasonModel
  ) : this(
      endDate = model.endDate,
      id = model.id,
      name = model.name,
      plantingSiteId = model.plantingSiteId,
      speciesTargets = model.speciesTargets.map { SpeciesTargetPayload(it) },
      startDate = model.startDate,
      status = model.status,
  )
}
