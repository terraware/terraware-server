package com.terraformation.backend.gis.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.GISAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.HealthState
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.PlantObservationNotFoundException
import com.terraformation.backend.db.tables.pojos.PlantObservationsRow
import com.terraformation.backend.gis.db.PlantObservationsStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/gis/plant_observations")
@RestController
@GISAppEndpoint
class PlantObservationsController(private val observStore: PlantObservationsStore) {
  @ApiResponse(
      responseCode = "200", description = "The plant observation was created successfully.")
  @Operation(
      summary =
          "Creates a new plant observation. Feature id must reference an existing Plant. " +
              "Plant observations can only be deleted as part of a feature deletion.")
  @PostMapping
  fun create(
      @RequestBody payload: CreateObservationRequestPayload
  ): CreateObservationResponsePayload {
    val newObservation = observStore.create(payload.toRow())
    return CreateObservationResponsePayload(ObservationResponse(newObservation))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified plant observation doesn't exist.")
  @GetMapping("/{plantObservationId}")
  fun get(@PathVariable plantObservationId: PlantObservationId): GetObservationResponsePayload {
    val observation =
        observStore.fetch(plantObservationId)
            ?: throw PlantObservationNotFoundException(plantObservationId)
    return GetObservationResponsePayload(ObservationResponse(observation))
  }

  @ApiResponse(responseCode = "200")
  @Operation(summary = "Fetch a list of the plant observations associated with a plant.")
  @GetMapping("/list/{featureId}")
  fun getList(@PathVariable featureId: FeatureId): ListObservationsResponsePayload {
    val list = observStore.fetchList(featureId)
    return ListObservationsResponsePayload(list.map { ObservationResponse(it) })
  }

  @ApiResponse(
      responseCode = "200", description = "The plant observation was updated successfully.")
  @ApiResponse404(description = "The specified plant observation doesn't exist.")
  @Operation(
      summary =
          "Update an existing plant observation. Cannot update the feature id. " +
              "Overwrites all other fields.")
  @PutMapping("/{plantObservationId}")
  fun update(
      @RequestBody payload: UpdateObservationRequestPayload,
      @PathVariable plantObservationId: PlantObservationId
  ): UpdateObservationResponsePayload {
    val updated = observStore.update(payload.toRow(plantObservationId))
    return UpdateObservationResponsePayload(ObservationResponse(updated))
  }

  // To delete a plant observation, the caller must delete the entire feature
  // through the Features API
}

data class CreateObservationRequestPayload(
    val featureId: FeatureId,
    val timestamp: Instant,
    val healthState: HealthState? = null,
    val flowers: Boolean? = null,
    val seeds: Boolean? = null,
    val pests: String? = null,
    @Schema(
        description = "Height in meters",
    )
    val height: Double? = null,
    @Schema(description = "Diameter at breast height in meters")
    val diameterAtBreastHeight: Double? = null,
) {
  fun toRow(): PlantObservationsRow {
    return PlantObservationsRow(
        featureId = featureId,
        timestamp = timestamp,
        healthStateId = healthState,
        flowers = flowers,
        seeds = seeds,
        pests = pests,
        height = height,
        dbh = diameterAtBreastHeight,
    )
  }
}

data class UpdateObservationRequestPayload(
    val timestamp: Instant,
    val healthState: HealthState? = null,
    val flowers: Boolean? = null,
    val seeds: Boolean? = null,
    val pests: String? = null,
    @Schema(
        description = "Height in meters",
    )
    val height: Double? = null,
    @Schema(description = "Diameter at breast height in meters")
    val diameterAtBreastHeight: Double? = null,
) {
  fun toRow(id: PlantObservationId): PlantObservationsRow {
    return PlantObservationsRow(
        id = id,
        timestamp = timestamp,
        healthStateId = healthState,
        flowers = flowers,
        seeds = seeds,
        pests = pests,
        height = height,
        dbh = diameterAtBreastHeight,
    )
  }
}

data class ObservationResponse(
    val id: PlantObservationId,
    val featureId: FeatureId,
    val timestamp: Instant,
    val healthState: HealthState? = null,
    val flowers: Boolean? = null,
    val seeds: Boolean? = null,
    val pests: String? = null,
    @Schema(
        description = "Height in meters",
    )
    val height: Double? = null,
    @Schema(description = "Diameter at breast height in meters")
    val diameterAtBreastHeight: Double? = null,
) {
  constructor(
      row: PlantObservationsRow
  ) : this(
      row.id!!,
      row.featureId!!,
      row.timestamp!!,
      row.healthStateId,
      row.flowers,
      row.seeds,
      row.pests,
      row.height,
      row.dbh)
}

data class CreateObservationResponsePayload(val resp: ObservationResponse) : SuccessResponsePayload

data class ListObservationsResponsePayload(val list: List<ObservationResponse>) :
    SuccessResponsePayload

data class GetObservationResponsePayload(val resp: ObservationResponse) : SuccessResponsePayload

data class UpdateObservationResponsePayload(val resp: ObservationResponse) : SuccessResponsePayload
