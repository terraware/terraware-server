package com.terraformation.backend.splat.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse202
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse422
import com.terraformation.backend.api.ErrorDetails
import com.terraformation.backend.api.SimpleErrorResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.splat.ObservationSplatModel
import com.terraformation.backend.splat.SplatGenerationFailedException
import com.terraformation.backend.splat.SplatNotReadyException
import com.terraformation.backend.splat.SplatService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@ConditionalOnBean(SplatService::class)
@RequestMapping
@RestController
@TrackingEndpoint
class SplatsController(
    private val splatService: SplatService,
) {
  @ApiResponse200
  @ApiResponse404("The observation does not exist.")
  @GetMapping("/api/v1/tracking/observations/{observationId}/splats")
  @Operation(summary = "Gets information about the 3D Gaussian splat models from an observation.")
  fun listObservationSplats(
      @PathVariable observationId: ObservationId,
      @Parameter(description = "If present, only list splats for this monitoring plot.")
      @RequestParam
      monitoringPlotId: MonitoringPlotId?,
      @Parameter(
          description = "If present, only return information about the splat for this video file."
      )
      @RequestParam
      fileId: FileId?,
  ): ListObservationSplatsResponsePayload {
    val models = splatService.listObservationSplats(observationId, monitoringPlotId, fileId)

    return ListObservationSplatsResponsePayload(models.map { ObservationSplatPayload.of(it) })
  }

  @ApiResponse200
  @ApiResponse202("The video is still being processed and the model is not ready yet.")
  @ApiResponse404(
      "The plot observation does not exist, or does not have a video with the requested ID."
  )
  @ApiResponse422("The system was unable to generate a splat from the requested file.")
  @GetMapping("/api/v1/tracking/observations/{observationId}/splats/{fileId}")
  @Operation(
      summary = "Downloads a 3D model of a Gaussian splat from an observation video.",
  )
  fun getObservationSplatFile(
      @PathVariable observationId: ObservationId,
      @PathVariable fileId: FileId,
  ): ResponseEntity<*> {
    return try {
      splatService.readObservationSplat(observationId, fileId).toResponseEntity()
    } catch (_: SplatGenerationFailedException) {
      ResponseEntity.unprocessableEntity()
          .body(SimpleErrorResponsePayload(ErrorDetails("Splat generation failed.")))
    } catch (_: SplatNotReadyException) {
      ResponseEntity.accepted()
          .body(SimpleErrorResponsePayload(ErrorDetails("Splat is not ready yet.")))
    }
  }

  @ApiResponse200
  @ApiResponse404(
      "The plot observation does not exist, or does not have a video with the requested ID."
  )
  @PostMapping("/api/v1/tracking/observations/{observationId}/splats")
  @Operation(
      summary =
          "Initiates the generation of a 3D model of a Gaussian splat from an observation video.",
      description = "If the video was already being processed, returns a success response.",
  )
  fun generateObservationSplatFile(
      @PathVariable observationId: ObservationId,
      @RequestBody payload: GenerateSplatRequestPayload,
  ): SimpleSuccessResponsePayload {
    splatService.generateObservationSplat(observationId, payload.fileId)

    return SimpleSuccessResponsePayload()
  }
}

data class ObservationSplatPayload(
    val fileId: FileId,
    val monitoringPlotId: MonitoringPlotId,
    val status: AssetStatus,
) {
  companion object {
    fun of(model: ObservationSplatModel) =
        ObservationSplatPayload(
            fileId = model.fileId,
            monitoringPlotId = model.monitoringPlotId,
            status = model.assetStatus,
        )
  }
}

data class GenerateSplatRequestPayload(
    val fileId: FileId,
)

data class ListObservationSplatsResponsePayload(
    val splats: List<ObservationSplatPayload>,
) : SuccessResponsePayload
