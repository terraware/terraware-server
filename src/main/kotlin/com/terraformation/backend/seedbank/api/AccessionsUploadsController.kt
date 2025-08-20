package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.GetUploadStatusResponsePayload
import com.terraformation.backend.api.ResolveUploadRequestPayload
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.UploadFileResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.seedbank.db.AccessionImporter
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

@RequestMapping("/api/v2/seedbank/accessions/uploads")
@RestController
@SeedBankAppEndpoint
class AccessionsUploadsController(
    private val accessionImporter: AccessionImporter,
    private val uploadStore: UploadStore,
) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The file has been successfully received. It will be processed asynchronously; use " +
              "the ID returned in the response payload to poll for its status using the " +
              "`/api/v2/seedbank/accessions/uploads/{uploadId}` GET endpoint.",
  )
  @Operation(
      summary = "Uploads a list of accessions to add to the facility.",
      description =
          "The uploaded file must be in CSV format. A template with the correct headers may be " +
              "downloaded from the `/api/v2/seedbank/accessions/uploads/template` endpoint.",
  )
  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = "text/csv")])],
  )
  fun uploadAccessionsList(
      @RequestPart("file") file: MultipartFile,
      @RequestParam facilityId: FacilityId,
  ): UploadFileResponsePayload {
    val fileName = file.originalFilename ?: "accessions.csv"

    val uploadId =
        file.inputStream.use { uploadStream ->
          accessionImporter.receiveCsv(uploadStream, fileName, facilityId)
        }

    return UploadFileResponsePayload(uploadId)
  }

  @GetMapping("/template")
  @Operation(
      summary =
          "Gets a template file that contains the required header row for accessions list uploads."
  )
  @Produces("text/csv")
  fun getAccessionsListUploadTemplate(): ResponseEntity<ByteArray> {
    val body = accessionImporter.getCsvTemplate()
    return ResponseEntity.ok().contentType(MediaType.valueOf("text/csv")).body(body)
  }

  @GetMapping("/{uploadId}")
  @Operation(
      summary = "Gets the status of an accessions list uploaded previously.",
      description = "Clients may poll this endpoint to monitor the progress of the file.",
  )
  fun getAccessionsListUploadStatus(
      @PathVariable uploadId: UploadId
  ): GetUploadStatusResponsePayload {
    val model = uploadStore.fetchOneById(uploadId)
    return GetUploadStatusResponsePayload(model)
  }

  @ApiResponseSimpleSuccess
  @ApiResponse409(description = "The upload was not awaiting user action.")
  @Operation(
      summary = "Resolves the problems with an accessions list that is awaiting user action.",
      description =
          "This may only be called if the status of the upload is \"Awaiting User Action\".",
  )
  @PostMapping("/{uploadId}/resolve")
  fun resolveAccessionsListUpload(
      @PathVariable uploadId: UploadId,
      @RequestBody payload: ResolveUploadRequestPayload,
  ): SimpleSuccessResponsePayload {
    accessionImporter.resolveWarnings(uploadId, payload.overwriteExisting)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse409(description = "The upload was not awaiting user action.")
  @DeleteMapping("/{uploadId}")
  @Operation(
      summary = "Deletes an accessions list upload that is awaiting user action.",
      description =
          "This may only be called if the status of the upload is \"Awaiting User Action\".",
  )
  fun deleteAccessionsListUpload(@PathVariable uploadId: UploadId): SimpleSuccessResponsePayload {
    accessionImporter.cancelProcessing(uploadId)
    return SimpleSuccessResponsePayload()
  }
}
