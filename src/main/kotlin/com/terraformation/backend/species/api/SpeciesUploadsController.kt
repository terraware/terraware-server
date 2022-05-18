package com.terraformation.backend.species.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UploadProblemType
import com.terraformation.backend.db.UploadStatus
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.file.model.UploadModel
import com.terraformation.backend.file.model.UploadProblemModel
import com.terraformation.backend.species.db.SpeciesImporter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import javax.ws.rs.Produces
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
              "`/api/v1/species/uploads/{uploadId}` GET endpoint.")
  @Operation(
      summary = "Uploads a list of species to add to the organization.",
      description =
          "The uploaded file must be in CSV format. A template with the correct headers may be " +
              "downloaded from the `/api/v1/species/uploads/template` endpoint.")
  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = "text/csv")])],
  )
  fun uploadSpeciesList(
      @RequestPart("file") file: MultipartFile,
      @RequestParam("organizationId", required = true) organizationId: OrganizationId,
  ): UploadSpeciesListResponsePayload {
    val fileName = file.originalFilename ?: "species.csv"

    val uploadId =
        file.inputStream.use { uploadStream ->
          speciesImporter.receiveCsv(uploadStream, fileName, organizationId)
        }

    return UploadSpeciesListResponsePayload(uploadId)
  }

  @GetMapping("/template")
  @Operation(
      summary =
          "Gets a template file that contains the required header row for species list uploads.")
  @Produces("text/csv")
  fun getSpeciesListUploadTemplate(): ResponseEntity<String> {
    val body = speciesImporter.getCsvTemplate()
    return ResponseEntity.ok().contentType(MediaType.valueOf("text/csv")).body(body)
  }

  @GetMapping("/{uploadId}")
  @Operation(
      summary = "Gets the status of a species list uploaded previously.",
      description = "Clients may poll this endpoint to monitor the progress of the file.")
  fun getSpeciesListUploadStatus(
      @PathVariable uploadId: UploadId
  ): GetSpeciesUploadStatusResponsePayload {
    val model = uploadStore.fetchOneById(uploadId)
    return GetSpeciesUploadStatusResponsePayload(model)
  }

  @ApiResponseSimpleSuccess
  @ApiResponse409(description = "The upload was not awaiting user action.")
  @Operation(
      summary = "Resolves the problems with a species list that is awaiting user action.",
      description =
          "This may only be called if the status of the upload is \"Awaiting User Action\".")
  @PostMapping("/{uploadId}/resolve")
  fun resolveSpeciesListUpload(
      @PathVariable uploadId: UploadId,
      @RequestBody payload: ResolveSpeciesUploadRequestPayload
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
          "This may only be called if the status of the upload is \"Awaiting User Action\".")
  fun deleteSpeciesListUpload(@PathVariable uploadId: UploadId): SimpleSuccessResponsePayload {
    speciesImporter.cancelProcessing(uploadId)
    return SimpleSuccessResponsePayload()
  }
}

data class UploadSpeciesListResponsePayload(
    @Schema(description = "ID of uploaded file. This may be used to poll for the file's status.")
    val id: UploadId
) : SuccessResponsePayload

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesUploadProblemPayload(
    @Schema(
        description =
            "Name of the field with the problem. Absent if the problem isn't specific to a " +
                "single field.")
    val fieldName: String?,
    @Schema(
        description = "Human-readable description of the problem.",
    )
    val message: String?,
    @Schema(description = "Position (row number) of the record with the problem.")
    val position: Int?,
    val type: UploadProblemType,
    @Schema(
        description =
            "The value that caused the problem. Absent if the problem wasn't caused by a " +
                "specific field value.")
    val value: String?,
) {
  constructor(
      model: UploadProblemModel
  ) : this(model.field, model.message, model.position, model.type, model.value)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetSpeciesUploadStatusDetailsPayload(
    val id: UploadId,
    val status: UploadStatus,
    @ArraySchema(
        schema =
            Schema(
                description =
                    "List of errors in the file. Errors prevent the file from being processed; " +
                        "the file needs to be modified to resolve them."))
    val errors: List<SpeciesUploadProblemPayload>?,
    @ArraySchema(
        schema =
            Schema(
                description =
                    "List of conditions that might cause the user to want to cancel the upload " +
                        "but that can be automatically resolved if desired."))
    val warnings: List<SpeciesUploadProblemPayload>?,
) {
  constructor(
      model: UploadModel
  ) : this(
      model.id,
      model.status,
      model.errors.map { SpeciesUploadProblemPayload(it) }.ifEmpty { null },
      model.warnings.map { SpeciesUploadProblemPayload(it) }.ifEmpty { null })

  @get:Schema(
      description =
          "True if the server is finished processing the file, either successfully or not.")
  val finished: Boolean
    get() = status.finished
}

data class GetSpeciesUploadStatusResponsePayload(
    val details: GetSpeciesUploadStatusDetailsPayload
) : SuccessResponsePayload {
  constructor(model: UploadModel) : this(GetSpeciesUploadStatusDetailsPayload(model))
}

data class ResolveSpeciesUploadRequestPayload(
    @Schema(
        description =
            "If true, the data for entries that already exist will be overwritten with the " +
                "values in the uploaded file. If false, only entries that don't already exist " +
                "will be imported.")
    val overwriteExisting: Boolean
)
