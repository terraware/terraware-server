package com.terraformation.backend.gis.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.GISAppEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.ShapeType
import com.terraformation.backend.gis.db.FeatureStore
import com.terraformation.backend.gis.model.FeatureModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/gis/feature")
@RestController
@GISAppEndpoint
class FeatureController(private val featureStore: FeatureStore) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The feature was created successfully. Response includes fields populated by the " +
              "server, including the feature id.")
  @Operation(summary = "Create a new feature.")
  @PostMapping
  fun create(@RequestBody payload: CreateFeatureRequestPayload): CreateFeatureResponsePayload {
    val updatedModel = featureStore.createFeature(payload.toModel())
    return CreateFeatureResponsePayload(FeatureResponse(updatedModel))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified feature doesn't exist.")
  @GetMapping("/{featureId}")
  fun read(@PathVariable featureId: Long): GetFeatureResponsePayload {
    val model =
        featureStore.fetchFeature(FeatureId(featureId))
            ?: throw NotFoundException("The feature with id $featureId doesn't exist.")

    return GetFeatureResponsePayload(FeatureResponse(model))
  }

  @ApiResponse(responseCode = "200", description = "The feature was updated successfully.")
  @ApiResponse404(description = "The specified feature doesn't exist.")
  @Operation(
      summary =
          "Update an existing feature. Overwrites all fields. Does not allow a feature " +
              "to be moved between layers (layerId cannot be updated)")
  @PutMapping("/{featureId}")
  fun update(
      @RequestBody payload: UpdateFeatureRequestPayload,
      @PathVariable featureId: Long,
  ): UpdateFeatureResponsePayload {
    try {
      val updatedModel = featureStore.updateFeature(payload.toModel(FeatureId(featureId)))
      return UpdateFeatureResponsePayload(FeatureResponse(updatedModel))
    } catch (e: FeatureNotFoundException) {
      throw NotFoundException(
          "The feature with id $featureId, layer ID ${payload.layerId} doesn't exist.")
    }
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified feature doesn't exist.")
  @Operation(
      summary =
          "Deletes an existing feature and all records that directly or indirectly reference that" +
              "feature. This includes but is not limited to plants, plant observations, photos, " +
              "and thumbnails.")
  @DeleteMapping("/{featureId}")
  fun delete(@PathVariable featureId: Long): DeleteFeatureResponsePayload {
    try {
      featureStore.deleteFeature(FeatureId(featureId))
      return DeleteFeatureResponsePayload(DeleteFeatureResponse(FeatureId(featureId)))
    } catch (e: FeatureNotFoundException) {
      throw NotFoundException("The feature with id $featureId doesn't exist.")
    }
  }
}

data class CreateFeatureRequestPayload(
    val layerId: LayerId,
    val shapeType: ShapeType,
    val altitude: Double? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
) {
  fun toModel(): FeatureModel {
    return FeatureModel(
        layerId = layerId,
        shapeType = shapeType,
        gpsHorizAccuracy = gpsHorizAccuracy,
        gpsVertAccuracy = gpsVertAccuracy,
        attrib = attrib,
        notes = notes,
        enteredTime = enteredTime,
    )
  }
}

data class UpdateFeatureRequestPayload(
    val layerId: LayerId,
    val shapeType: ShapeType,
    val altitude: Double? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
) {
  fun toModel(id: FeatureId): FeatureModel {
    return FeatureModel(
        id = id,
        layerId = layerId,
        shapeType = shapeType,
        gpsHorizAccuracy = gpsHorizAccuracy,
        gpsVertAccuracy = gpsVertAccuracy,
        attrib = attrib,
        notes = notes,
        enteredTime = enteredTime,
    )
  }
}

data class FeatureResponse(
    val id: FeatureId,
    val layerId: LayerId,
    val shapeType: ShapeType,
    val altitude: Double? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
) {
  constructor(
      model: FeatureModel
  ) : this(
      model.id!!,
      model.layerId,
      model.shapeType,
      model.geom?.firstPoint?.z,
      model.gpsHorizAccuracy,
      model.gpsVertAccuracy,
      model.attrib,
      model.notes,
      model.enteredTime)
}

data class DeleteFeatureResponse(
    val id: FeatureId,
)

data class CreateFeatureResponsePayload(val feature: FeatureResponse) : SuccessResponsePayload

data class GetFeatureResponsePayload(val feature: FeatureResponse) : SuccessResponsePayload

data class UpdateFeatureResponsePayload(val feature: FeatureResponse) : SuccessResponsePayload

data class DeleteFeatureResponsePayload(val id: DeleteFeatureResponse) : SuccessResponsePayload
