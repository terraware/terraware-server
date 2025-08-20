package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.VariableFileService
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@InternalEndpoint
@RequestMapping("/api/v1/document-producer")
@RestController
class ImagesController(
    private val variableFileService: VariableFileService,
) {
  @GetMapping(
      "/projects/{projectId}/images/{valueId}",
      produces =
          [MediaType.APPLICATION_JSON_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE],
  )
  @Operation(
      summary = "Gets the contents of an image variable value.",
      description =
          "Optional maxWidth and maxHeight parameters may be included to control the dimensions " +
              "of the image; the server will scale the original down as needed. If neither " +
              "parameter is specified, the original full-size image will be returned. The aspect " +
              "ratio of the original image is maintained, so the returned image may be smaller " +
              "than the requested width and height. If only maxWidth or only maxHeight is " +
              "supplied, the other dimension will be computed based on the original image's " +
              "aspect ratio.",
  )
  fun getProjectImageValue(
      @PathVariable projectId: ProjectId,
      @PathVariable valueId: VariableValueId,
      @RequestParam
      @Schema(
          description =
              "Maximum desired width in pixels. If neither this nor maxHeight is specified, the " +
                  "full-sized original image will be returned. If this is specified, an image no " +
                  "wider than this will be returned. The image may be narrower than this value " +
                  "if needed to preserve the aspect ratio of the original."
      )
      maxWidth: Int? = null,
      @RequestParam
      @Schema(
          description =
              "Maximum desired height in pixels. If neither this nor maxWidth is specified, the " +
                  "full-sized original image will be returned. If this is specified, an image no " +
                  "taller than this will be returned. The image may be shorter than this value " +
                  "if needed to preserve the aspect ratio of the original."
      )
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return variableFileService
        .readImageValue(projectId, valueId, maxWidth, maxHeight)
        .toResponseEntity()
  }

  @Operation(summary = "Save an image to a new variable value.")
  @PostMapping("/projects/{projectId}/images")
  fun uploadProjectImageValue(
      @PathVariable projectId: ProjectId,
      @RequestPart file: MultipartFile,
      @RequestPart(required = false) caption: String?,
      @RequestPart(required = false) citation: String?,
      @RequestPart @Schema(format = "int64", type = "integer") variableId: String,
      @RequestPart(required = false)
      @Schema(
          description =
              "If the variable is a list, which list position to use for the value. If not " +
                  "specified, the server will use the next available list position if the " +
                  "variable is a list, or will replace any existing image if the variable is " +
                  "not a list.",
          format = "int32",
          type = "integer",
      )
      listPosition: String? = null,
      @RequestPart(required = false)
      @Schema(
          description =
              "If the variable is a table column, value ID of the row the value should belong to.",
          format = "int64",
          type = "integer",
      )
      rowValueId: String? = null,
  ): UploadImageFileResponsePayload {
    val newMetadata =
        FileMetadata.of(
            contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES),
            filename = file.getFilename("file"),
            size = file.size,
        )

    val isAppend = listPosition == null

    val base =
        BaseVariableValueProperties(
            citation = citation,
            id = null,
            // If list position isn't specified, the value here will be ignored because isAppend
            // will be true.
            listPosition = listPosition?.toIntOrNull() ?: 0,
            projectId = projectId,
            rowValueId = rowValueId?.ifEmpty { null }?.let { VariableValueId(it.toLong()) },
            variableId = VariableId(variableId.toLong()),
        )

    val valueId =
        variableFileService.storeImageValue(file.inputStream, newMetadata, base, caption, isAppend)

    return UploadImageFileResponsePayload(valueId)
  }
}

data class UploadImageFileResponsePayload(val valueId: VariableValueId) : SuccessResponsePayload
