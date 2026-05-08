package com.terraformation.backend.splat.sqs

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.splat.CoordinateModel
import com.terraformation.backend.splat.ModelMetadataModel
import com.terraformation.backend.splat.SplatService
import io.awspring.cloud.sqs.annotation.SqsListener
import jakarta.inject.Named
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@ConditionalOnProperty("terraware.splatter.enabled")
@Named
class SplatsSqsListener(private val splatService: SplatService) {
  private val log = perClassLogger()

  @SqsListener($$"${terraware.splatter.response-queue-url}")
  fun receiveSplatterResponse(payload: SplatterResponsePayload) {
    log.info("Got response from Splatter service: $payload")

    if (payload.success) {
      splatService.recordSplatSuccess(payload.jobId, payload.modelMetadata?.toModel())
    } else {
      splatService.recordSplatError(
          payload.jobId,
          payload.errorMessage ?: "No error message received",
      )
    }

    if (payload.birdnetSuccess != null) {
      if (payload.birdnetSuccess) {
        splatService.recordBirdnetSuccess(payload.jobId)
      } else {
        splatService.recordBirdnetError(
            payload.jobId,
            payload.birdnetErrorMessage ?: "No error message received",
        )
      }
    }
  }
}

data class SplatterResponseOutputPayload(
    val bucket: String,
    val key: String,
)

data class SplatterResponsePayload(
    val birdnetErrorMessage: String? = null,
    val birdnetOutput: SplatterResponseOutputPayload? = null,
    val birdnetSuccess: Boolean? = null,
    val errorMessage: String?,
    val jobId: FileId,
    val modelMetadata: SplatterResponseModelMetadataPayload? = null,
    val output: SplatterResponseOutputPayload?,
    val success: Boolean,
)

data class GeoJsonPointPayload(
    val type: String = "Point",
    val coordinates: List<Double> = emptyList(),
)

data class SplatterResponseModelMetadataPayload(
    val groundColor: String?,
    val sceneBounds: GeoJsonPointPayload? = null,
    val skyColor: String?,
) {
  fun toModel(): ModelMetadataModel {
    val parsedSceneBounds = sceneBounds?.run {
      if (coordinates.size < 4) {
        log.warn("Ignoring scene_bounds with fewer than 4 coordinates: $coordinates")
        null
      } else {
        CoordinateModel(
            x = coordinates[0],
            y = coordinates[1],
            z = coordinates[2],
            m = coordinates[3],
        )
      }
    }
    return ModelMetadataModel(
        groundColor = groundColor,
        sceneBounds = parsedSceneBounds,
        skyColor = skyColor,
    )
  }

  companion object {
    private val log = perClassLogger()
  }
}
