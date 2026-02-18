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
import com.terraformation.backend.api.addImmutableCacheControlHeaders
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SplatAnnotationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.splat.CoordinateModel
import com.terraformation.backend.splat.ExistingSplatAnnotationModel
import com.terraformation.backend.splat.ObservationBirdnetResultModel
import com.terraformation.backend.splat.ObservationSplatModel
import com.terraformation.backend.splat.SplatAnnotationModel
import com.terraformation.backend.splat.SplatGenerationFailedException
import com.terraformation.backend.splat.SplatInfoModel
import com.terraformation.backend.splat.SplatNotReadyException
import com.terraformation.backend.splat.SplatService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import java.math.BigDecimal
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@ConditionalOnProperty("terraware.splatter.enabled")
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
  @ApiResponse404("The observation does not exist.")
  @GetMapping("/api/v1/tracking/observations/{observationId}/birdnet")
  @Operation(summary = "Gets information about BirdNet results from an observation.")
  fun getObservationBirdnetResults(
      @PathVariable observationId: ObservationId,
      @Parameter(description = "If present, only list results for this monitoring plot.")
      @RequestParam
      monitoringPlotId: MonitoringPlotId?,
      @Parameter(
          description = "If present, only return information about the result for this video file."
      )
      @RequestParam
      fileId: FileId?,
  ): ListObservationBirdnetResultsResponsePayload {
    val models = splatService.listObservationBirdnetResults(observationId, monitoringPlotId, fileId)

    return ListObservationBirdnetResultsResponsePayload(
        models.map { ObservationBirdnetResultPayload.of(it) }
    )
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
      splatService
          .readObservationSplat(observationId, fileId)
          .toResponseEntity(addHeaders = addImmutableCacheControlHeaders)
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

  @ApiResponse200
  @ApiResponse404(
      "The plot observation does not exist, or does not have a splat for the requested file ID."
  )
  @GetMapping("/api/v1/tracking/observations/{observationId}/splats/{fileId}/info")
  @Operation(summary = "Gets the info for a splat model, such as the list of annotations.")
  fun listSplatDetails(
      @PathVariable observationId: ObservationId,
      @PathVariable fileId: FileId,
  ): GetObservationSplatInfoResponsePayload {
    val infoModel = splatService.getObservationSplatInfo(observationId, fileId)

    return GetObservationSplatInfoResponsePayload(infoModel)
  }

  @ApiResponse200
  @ApiResponse404(
      "The plot observation does not exist, or does not have a splat for the requested file ID."
  )
  @GetMapping("/api/v1/tracking/observations/{observationId}/splats/{fileId}/annotations")
  @Operation(summary = "Use /info instead", deprecated = true)
  fun listObservationSplatAnnotations(
      @PathVariable observationId: ObservationId,
      @PathVariable fileId: FileId,
  ): ListObservationSplatAnnotationsResponsePayload {
    val models = splatService.listObservationSplatAnnotations(observationId, fileId)

    return ListObservationSplatAnnotationsResponsePayload(
        models.map { SplatAnnotationPayload.of(it) }
    )
  }

  @ApiResponse200
  @ApiResponse404(
      "The plot observation does not exist, or does not have a splat for the requested file ID."
  )
  @PostMapping("/api/v1/tracking/observations/{observationId}/splats/{fileId}/annotations")
  @Operation(
      summary = "Sets the list of annotations for a splat model.",
      description =
          "Updates existing annotations that have IDs, deletes annotations not in the list, and creates new annotations without IDs.",
  )
  fun setObservationSplatAnnotations(
      @PathVariable observationId: ObservationId,
      @PathVariable fileId: FileId,
      @RequestBody payload: SetSplatAnnotationsRequestPayload,
  ): SimpleSuccessResponsePayload {
    val annotations = payload.annotations.map { it.toModel(fileId) }
    splatService.setObservationSplatAnnotations(observationId, fileId, annotations)

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

data class CoordinatePayload(val x: BigDecimal, val y: BigDecimal, val z: BigDecimal) {
  companion object {
    fun of(model: CoordinateModel) = CoordinatePayload(model.x, model.y, model.z)
  }
}

data class SplatAnnotationPayload(
    val bodyText: String?,
    val cameraPosition: CoordinatePayload?,
    val fileId: FileId,
    val id: SplatAnnotationId,
    val label: String?,
    val position: CoordinatePayload,
    val title: String,
) {
  companion object {
    fun of(model: ExistingSplatAnnotationModel) =
        SplatAnnotationPayload(
            bodyText = model.bodyText,
            cameraPosition = model.cameraPosition?.let { CoordinatePayload.of(it) },
            fileId = model.fileId,
            id = model.id,
            label = model.label,
            position = CoordinatePayload.of(model.position),
            title = model.title,
        )
  }
}

data class GetObservationSplatInfoResponsePayload(
    val annotations: List<SplatAnnotationPayload>,
    val cameraPosition: CoordinatePayload?,
    val originPosition: CoordinatePayload?,
) : SuccessResponsePayload {
  constructor(
      model: SplatInfoModel
  ) : this(
      annotations = model.annotations.map { SplatAnnotationPayload.of(it) },
      cameraPosition = model.cameraPosition?.let { CoordinatePayload.of(it) },
      originPosition = model.originPosition?.let { CoordinatePayload.of(it) },
  )
}

data class ListObservationSplatAnnotationsResponsePayload(
    val annotations: List<SplatAnnotationPayload>,
) : SuccessResponsePayload

data class SetSplatAnnotationRequestPayload(
    val bodyText: String?,
    val cameraPosition: CoordinatePayload?,
    val id: SplatAnnotationId?,
    val label: String?,
    val position: CoordinatePayload,
    val title: String,
) {
  fun toModel(fileId: FileId): SplatAnnotationModel<SplatAnnotationId?> =
      SplatAnnotationModel(
          bodyText = bodyText,
          cameraPosition = cameraPosition?.let { CoordinateModel(it.x, it.y, it.z) },
          fileId = fileId,
          id = id,
          label = label,
          position = CoordinateModel(position.x, position.y, position.z),
          title = title,
      )
}

data class SetSplatAnnotationsRequestPayload(
    val annotations: List<SetSplatAnnotationRequestPayload>,
)

data class ObservationBirdnetResultPayload(
    val fileId: FileId,
    val monitoringPlotId: MonitoringPlotId,
    val status: AssetStatus,
    val resultsStorageUrl: String?,
) {
  companion object {
    fun of(model: ObservationBirdnetResultModel) =
        ObservationBirdnetResultPayload(
            fileId = model.fileId,
            monitoringPlotId = model.monitoringPlotId,
            status = model.assetStatus,
            resultsStorageUrl = model.resultsStorageUrl?.toString(),
        )
  }
}

data class ListObservationBirdnetResultsResponsePayload(
    val results: List<ObservationBirdnetResultPayload>,
) : SuccessResponsePayload
