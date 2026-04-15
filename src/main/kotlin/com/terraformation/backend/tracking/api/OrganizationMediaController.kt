package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.tracking.OrganizationMediaService
import io.swagger.v3.oas.annotations.Operation
import org.locationtech.jts.geom.Point
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/organizations/{organizationId}/media")
@RestController
@CustomerEndpoint
class OrganizationMediaController(
    private val organizationMediaService: OrganizationMediaService,
) {
  @ApiResponse200
  @Operation(summary = "Uploads a photo or video associated with an organization.")
  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBodyPhotoFile
  fun uploadOrganizationMediaFile(
      @PathVariable organizationId: OrganizationId,
      @RequestPart("file") file: MultipartFile,
      @RequestPart("payload") payload: UploadOrganizationMediaRequestPayload?,
  ): UploadOrganizationMediaResponsePayload {
    val fileId =
        organizationMediaService.upload(
            organizationId = organizationId,
            file = file,
            caption = payload?.caption,
        )
    return UploadOrganizationMediaResponsePayload(fileId)
  }

  @ApiResponse200
  @ApiResponse404("The media file does not exist in this organization.")
  @GetMapping("/{fileId}")
  @Operation(
      summary =
          "Downloads an organization media file. For videos, this is the raw video file, not a " +
              "streamable version."
  )
  fun downloadOrganizationMediaFile(
      @PathVariable organizationId: OrganizationId,
      @PathVariable fileId: FileId,
  ): ResponseEntity<*> {
    return organizationMediaService.read(organizationId, fileId).toResponseEntity()
  }

  @ApiResponse200
  @ApiResponse404("The media file does not exist in this organization.")
  @PutMapping("/{fileId}")
  @Operation(summary = "Updates an organization media file's metadata.")
  fun updateOrganizationMediaFile(
      @PathVariable organizationId: OrganizationId,
      @PathVariable fileId: FileId,
      @RequestBody payload: UpdateOrganizationMediaRequestPayload,
  ): SimpleSuccessResponsePayload {
    organizationMediaService.update(
        organizationId = organizationId,
        fileId = fileId,
        caption = payload.caption,
        gpsCoordinates = payload.gpsCoordinates,
    )
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404("The media file does not exist in this organization.")
  @DeleteMapping("/{fileId}")
  @Operation(summary = "Deletes an organization media file.")
  fun deleteOrganizationMediaFile(
      @PathVariable organizationId: OrganizationId,
      @PathVariable fileId: FileId,
  ): SimpleSuccessResponsePayload {
    organizationMediaService.delete(organizationId, fileId)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404("The media file does not exist in this organization, or is not a video.")
  @GetMapping("/{fileId}/stream")
  @Operation(summary = "Gets Mux stream details for an organization video.")
  fun getOrganizationMediaFileStream(
      @PathVariable organizationId: OrganizationId,
      @PathVariable fileId: FileId,
  ): GetOrganizationMediaStreamResponsePayload {
    val stream = organizationMediaService.getStream(organizationId, fileId)
    return GetOrganizationMediaStreamResponsePayload(stream)
  }
}

data class UploadOrganizationMediaRequestPayload(
    val caption: String? = null,
)

data class UploadOrganizationMediaResponsePayload(
    val fileId: FileId,
) : SuccessResponsePayload

data class UpdateOrganizationMediaRequestPayload(
    val caption: String? = null,
    val gpsCoordinates: Point? = null,
)

data class GetOrganizationMediaStreamResponsePayload(
    val fileId: FileId,
    val playbackId: String,
    val playbackToken: String,
) : SuccessResponsePayload {
  constructor(
      model: MuxStreamModel
  ) : this(
      fileId = model.fileId,
      playbackId = model.playbackId,
      playbackToken = model.playbackToken,
  )
}
