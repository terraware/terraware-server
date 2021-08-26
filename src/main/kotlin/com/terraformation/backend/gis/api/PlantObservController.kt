package com.terraformation.backend.gis.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.GISAppEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.HealthState
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.PlantObservationNotFoundException
import com.terraformation.backend.db.tables.pojos.PlantObservationsRow
import com.terraformation.backend.gis.db.PlantObservStore
import io.swagger.v3.oas.annotations.Operation
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
class PlantObservController(private val observStore: PlantObservStore) {
  @ApiResponse(
      responseCode = "200", description = "The plant observation was created successfully.")
  @Operation(
      summary =
          "Creates a new plant observation. Feature id must reference an existing Plant. " +
              "Plant observations can only be deleted as part of a feature deletion.")
  @PostMapping
  fun create(@RequestBody payload: CreateObservRequestPayload): CreateObservResponsePayload {
    val newObservation = observStore.create(payload.featureId, payload.toRow())
    return CreateObservResponsePayload(ObservResponse(newObservation))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified plant observation doesn't exist.")
  @GetMapping("/{plantObservationId}")
  fun get(@PathVariable plantObservationId: Long): GetObservResponsePayload {
    val id = PlantObservationId(plantObservationId)
    val observation =
        observStore.fetch(id)
            ?: throw NotFoundException("The plant observation with id $id doesn't exist.")
    return GetObservResponsePayload(ObservResponse(observation))
  }

  @ApiResponse(responseCode = "200")
  @Operation(summary = "Fetch a list of the plant observations associated with a plant.")
  @GetMapping("/list/{featureId}")
  fun getList(@PathVariable featureId: Long): GetObservListResponsePayload {
    val list = observStore.fetchList(FeatureId(featureId))
    return GetObservListResponsePayload(list.map { ObservResponse(it) })
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
      @RequestBody payload: UpdateObservRequestPayload,
      @PathVariable plantObservationId: Long
  ): UpdateObservResponsePayload {
    val id = PlantObservationId(plantObservationId)
    try {
      val updated = observStore.update(id, payload.toRow(id))
      return UpdateObservResponsePayload(ObservResponse(updated))
    } catch (e: PlantObservationNotFoundException) {
      throw NotFoundException("The plant observation with id $id doesn't exist.")
    }
  }

  // To delete a plant observation,
  // the caller must delete the entire feature through the Features API
}

data class CreateObservRequestPayload(
    val featureId: FeatureId,
    val timestamp: Instant,
    val healthStateId: HealthState? = null,
    val flowers: Boolean? = null,
    val seeds: Boolean? = null,
    val pests: String? = null,
    val heightInMeters: Double? = null,
    val diameterAtBreastHeightInMeters: Double? = null,
) {
  fun toRow(): PlantObservationsRow {
    return PlantObservationsRow(
        featureId = featureId,
        timestamp = timestamp,
        healthStateId = healthStateId,
        flowers = flowers,
        seeds = seeds,
        pests = pests,
        height = heightInMeters,
        dbh = diameterAtBreastHeightInMeters,
    )
  }
}

data class UpdateObservRequestPayload(
    val timestamp: Instant,
    val healthStateId: HealthState? = null,
    val flowers: Boolean? = null,
    val seeds: Boolean? = null,
    val pests: String? = null,
    val heightInMeters: Double? = null,
    val diameterAtBreastHeightInMeters: Double? = null,
) {
  fun toRow(id: PlantObservationId): PlantObservationsRow {
    return PlantObservationsRow(
        id = id,
        timestamp = timestamp,
        healthStateId = healthStateId,
        flowers = flowers,
        seeds = seeds,
        pests = pests,
        height = heightInMeters,
        dbh = diameterAtBreastHeightInMeters,
    )
  }
}

data class ObservResponse(
    val id: PlantObservationId,
    val featureId: FeatureId,
    val timestamp: Instant,
    val healthStateId: HealthState? = null,
    val flowers: Boolean? = null,
    val seeds: Boolean? = null,
    val pests: String? = null,
    val heightInMeters: Double? = null,
    val diameterAtBreastHeightInMeters: Double? = null,
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

data class CreateObservResponsePayload(val resp: ObservResponse) : SuccessResponsePayload

data class GetObservListResponsePayload(val list: List<ObservResponse>) : SuccessResponsePayload

data class GetObservResponsePayload(val resp: ObservResponse) : SuccessResponsePayload

data class UpdateObservResponsePayload(val resp: ObservResponse) : SuccessResponsePayload
