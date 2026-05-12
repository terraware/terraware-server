package com.terraformation.backend.splat.sqs

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.terraformation.backend.db.PointWithMDeserializer
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.splat.CoordinateModel
import com.terraformation.backend.splat.ModelMetadataModel
import com.terraformation.backend.splat.SplatService
import io.awspring.cloud.sqs.annotation.SqsListener
import jakarta.inject.Named
import org.locationtech.jts.geom.MultiPoint
import org.locationtech.jts.geom.Point
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

data class SplatterResponseModelMetadataPayload(
    val groundColor: String?,
    val groundPlane: MultiPoint? = null,
    @JsonDeserialize(using = PointWithMDeserializer::class) //
    val sceneBounds: Point? = null,
    val skyColor: String?,
) {
  fun toModel(): ModelMetadataModel {
    val parsedSceneBounds = sceneBounds?.run {
      if (coordinate.m.isNaN()) {
        log.warn("Ignoring scene_bounds with no M coordinate: $this")
        null
      } else {
        CoordinateModel(
            x = x,
            y = y,
            z = coordinate.z,
            m = coordinate.m,
        )
      }
    }
    val parsedGroundPlane = groundPlane?.run {
      // SRID is 4326 from GeometryDeserializer; reset to 0 for correctness since these are
      // cartesian coordinates, not geographic. Not technically necessary since we only
      // extract coordinate values and never use the geometry's SRID.
      srid = 0
      if (numGeometries < 3) {
        log.warn("Ignoring ground_plane with fewer than 3 points")
        null
      } else if ((0..<numGeometries).any { i -> getGeometryN(i).coordinate.z.isNaN() }) {
        log.warn("Ignoring ground_plane with a point missing Z coordinate")
        null
      } else {
        (0..<numGeometries).map { i ->
          val p = getGeometryN(i) as Point
          CoordinateModel(x = p.x, y = p.y, z = p.coordinate.z)
        }
      }
    }

    return ModelMetadataModel(
        groundColor = groundColor,
        groundPlane = parsedGroundPlane,
        sceneBounds = parsedSceneBounds,
        skyColor = skyColor,
    )
  }

  companion object {
    private val log = perClassLogger()
  }
}
