package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse202
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse422
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.ErrorDetails
import com.terraformation.backend.api.SimpleErrorResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.addImmutableCacheControlHeaders
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.splat.SplatGenerationFailedException
import com.terraformation.backend.splat.SplatNotReadyException
import com.terraformation.backend.splat.SplatService
import com.terraformation.backend.splat.api.GenerateSplatRequestPayload
import com.terraformation.backend.splat.api.GetObservationSplatInfoResponsePayload
import com.terraformation.backend.splat.api.SetSplatAnnotationsRequestPayload
import io.swagger.v3.oas.annotations.Operation
import jakarta.ws.rs.ServiceUnavailableException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/organizations/{organizationId}/splats")
@RestController
@CustomerEndpoint
class OrganizationSplatsController(
    @Autowired(required = false) private val splatServiceDependency: SplatService?,
) {
  private val splatService: SplatService
    get() =
        splatServiceDependency
            ?: throw ServiceUnavailableException("Splatter service is not enabled")

  @ApiResponse200
  @ApiResponse202("The video is still being processed and the model is not ready yet.")
  @ApiResponse404("The media file does not exist in this organization.")
  @ApiResponse422("The system was unable to generate a splat from the requested file.")
  @GetMapping("/{fileId}")
  @Operation(summary = "Downloads a splat model for an organization video.")
  fun getOrganizationSplatFile(
      @PathVariable organizationId: OrganizationId,
      @PathVariable fileId: FileId,
  ): ResponseEntity<*> {
    return try {
      splatService
          .readOrganizationSplat(organizationId, fileId)
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
  @ApiResponse404("The media file does not exist in this organization.")
  @PostMapping
  @Operation(summary = "Initiates splat generation from an organization video.")
  fun generateOrganizationSplat(
      @PathVariable organizationId: OrganizationId,
      @RequestBody payload: GenerateSplatRequestPayload,
  ): SimpleSuccessResponsePayload {
    splatService.generateOrganizationMediaSplat(organizationId, payload.fileId)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404("The media file does not exist in this organization, or has no splat.")
  @GetMapping("/{fileId}/info")
  @Operation(summary = "Gets splat info and annotations for an organization video.")
  fun getOrganizationSplatInfo(
      @PathVariable organizationId: OrganizationId,
      @PathVariable fileId: FileId,
  ): GetObservationSplatInfoResponsePayload {
    val infoModel = splatService.getOrganizationSplatInfo(organizationId, fileId)
    return GetObservationSplatInfoResponsePayload(infoModel)
  }

  @ApiResponse200
  @ApiResponse404("The media file does not exist in this organization, or has no splat.")
  @PostMapping("/{fileId}/annotations")
  @Operation(summary = "Sets annotations for a splat model of an organization video.")
  fun setOrganizationSplatAnnotations(
      @PathVariable organizationId: OrganizationId,
      @PathVariable fileId: FileId,
      @RequestBody payload: SetSplatAnnotationsRequestPayload,
  ): SimpleSuccessResponsePayload {
    val annotations = payload.annotations.map { it.toModel(fileId) }
    splatService.setOrganizationSplatAnnotations(organizationId, fileId, annotations)
    return SimpleSuccessResponsePayload()
  }
}
