package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.NewPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonStore
import io.swagger.v3.oas.annotations.Operation
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@TrackingEndpoint
@RequestMapping("/api/v1/planting-seasons")
@RestController
class PlantingSeasonsController(
    private val plantingSeasonStore: PlantingSeasonStore,
) {

  @ApiResponse200
  @Operation(summary = "Creates a new planting season.")
  @PostMapping
  fun createPlantingSeason(
      @RequestBody payload: CreatePlantingSeasonRequestPayload
  ): CreatePlantingSeasonResponsePayload {
    payload.validate()

    val plantingSeasonId = plantingSeasonStore.create(payload.toModel())
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
}

data class CreatePlantingSeasonRequestPayload(
    val endDate: LocalDate,
    val name: String,
    val plantingSiteId: PlantingSiteId,
    val startDate: LocalDate,
) {
  fun validate() {
    if (startDate > endDate) {
      throw IllegalArgumentException("Start date must be before end date")
    }
  }

  fun toModel(): NewPlantingSeasonModel =
      NewPlantingSeasonModel(
          endDate = endDate,
          id = null,
          name = name,
          plantingSiteId = plantingSiteId,
          startDate = startDate,
      )
}

data class CreatePlantingSeasonResponsePayload(val id: PlantingSeasonId) : SuccessResponsePayload

data class GetPlantingSeasonResponsePayload(
    val season: PlantingSeasonPayload,
) : SuccessResponsePayload {
  constructor(model: ExistingPlantingSeasonModel) : this(PlantingSeasonPayload(model))
}

data class PlantingSeasonPayload(
    val endDate: LocalDate,
    val id: PlantingSeasonId,
    val name: String,
    val plantingSiteId: PlantingSiteId,
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
      startDate = model.startDate,
      status = model.status!!,
  )
}
