package com.terraformation.backend.species.api

import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.GetUploadStatusResponsePayload
import com.terraformation.backend.api.ResolveUploadRequestPayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.UploadFileResponsePayload
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.species.db.SpeciesImporter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.Produces
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@CustomerEndpoint
@RequestMapping("/api/v1/species/uploads")
@RestController
class SpeciesUploadsController(
    private val speciesImporter: SpeciesImporter,
    private val uploadStore: UploadStore,
) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The file has been successfully received. It will be processed asynchronously; use " +
              "the ID returned in the response payload to poll for its status using the " +
              "`/api/v1/species/uploads/{uploadId}` GET endpoint.",
  )
  @Operation(
      summary = "Uploads a list of species to add to the organization.",
      description =
          "The uploaded file must be in CSV format. A template with the correct headers may be " +
              "downloaded from the `/api/v1/species/uploads/template` endpoint.",
  )
  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = "text/csv")])],
  )
  fun uploadSpeciesList(
      @RequestPart("file") file: MultipartFile,
      @RequestParam organizationId: OrganizationId,
  ): UploadFileResponsePayload {
    val fileName = file.originalFilename ?: "species.csv"

    val uploadId =
        file.inputStream.use { uploadStream ->
          speciesImporter.receiveCsv(uploadStream, fileName, organizationId)
        }

    return UploadFileResponsePayload(uploadId)
  }

  @GetMapping("/template")
  @Operation(
      summary =
          "Gets a template file that contains the required header row for species list uploads."
  )
  @Produces("text/csv")
  fun getSpeciesListUploadTemplate(): ResponseEntity<ByteArray> {
    val body = speciesImporter.getCsvTemplate()
    return ResponseEntity.ok().contentType(MediaType.valueOf("text/csv")).body(body)
  }

  @GetMapping("/{uploadId}")
  @Operation(
      summary = "Gets the status of a species list uploaded previously.",
      description = "Clients may poll this endpoint to monitor the progress of the file.",
  )
  fun getSpeciesListUploadStatus(@PathVariable uploadId: UploadId): GetUploadStatusResponsePayload {
    val model = uploadStore.fetchOneById(uploadId)
    return GetUploadStatusResponsePayload(model)
  }

  @ApiResponseSimpleSuccess
  @ApiResponse409(description = "The upload was not awaiting user action.")
  @Operation(
      summary = "Resolves the problems with a species list that is awaiting user action.",
      description =
          "This may only be called if the status of the upload is \"Awaiting User Action\".",
  )
  @PostMapping("/{uploadId}/resolve")
  fun resolveSpeciesListUpload(
      @PathVariable uploadId: UploadId,
      @RequestBody payload: ResolveUploadRequestPayload,
  ): SimpleSuccessResponsePayload {
    speciesImporter.resolveWarnings(uploadId, payload.overwriteExisting)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse409(description = "The upload was not awaiting user action.")
  @DeleteMapping("/{uploadId}")
  @Operation(
      summary = "Deletes a species list upload that is awaiting user action.",
      description =
          "This may only be called if the status of the upload is \"Awaiting User Action\".",
  )
  fun deleteSpeciesListUpload(@PathVariable uploadId: UploadId): SimpleSuccessResponsePayload {
    speciesImporter.cancelProcessing(uploadId)
    return SimpleSuccessResponsePayload()
  }
}
