package com.terraformation.backend.nursery.api

import com.terraformation.backend.api.GetUploadStatusResponsePayload
import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.UploadFileResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.nursery.db.BatchImporter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.Produces
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

@NurseryEndpoint
@RequestMapping("/api/v1/nursery/batches/uploads")
@RestController
class BatchesUploadsController(
    private val batchImporter: BatchImporter,
    private val uploadStore: UploadStore,
) {
  @GetMapping("/template")
  @Operation(
      summary =
          "Gets a template file that contains the required header row for seedling batch uploads."
  )
  @Produces("text/csv")
  fun getSeedlingBatchesUploadTemplate(): ResponseEntity<ByteArray> {
    val body = batchImporter.getCsvTemplate()
    return ResponseEntity.ok().contentType(MediaType.valueOf("text/csv")).body(body)
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The file has been successfully received. It will be processed asynchronously; use " +
              "the ID returned in the response payload to poll for its status using the " +
              "`/api/v1/nursery/batches/uploads/{uploadId}` GET endpoint.",
  )
  @Operation(
      summary = "Uploads a list of seedling batches to add to the nursery.",
      description =
          "The uploaded file must be in CSV format. A template with the correct headers may be " +
              "downloaded from the `/api/v1/nursery/batches/uploads/template` endpoint.",
  )
  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = "text/csv")])],
  )
  fun uploadSeedlingBatchesList(
      @RequestPart("file") file: MultipartFile,
      @RequestParam facilityId: FacilityId,
  ): UploadFileResponsePayload {
    val fileName = file.originalFilename ?: "batches.csv"

    val uploadId =
        file.inputStream.use { uploadStream ->
          batchImporter.receiveCsv(uploadStream, fileName, facilityId)
        }

    return UploadFileResponsePayload(uploadId)
  }

  @GetMapping("/{uploadId}")
  @Operation(
      summary = "Gets the status of a seedling batches list uploaded previously.",
      description = "Clients may poll this endpoint to monitor the progress of the file.",
  )
  fun getSeedlingBatchesListUploadStatus(
      @PathVariable uploadId: UploadId
  ): GetUploadStatusResponsePayload {
    val model = uploadStore.fetchOneById(uploadId)
    return GetUploadStatusResponsePayload(model)
  }
}
