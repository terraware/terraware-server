package com.terraformation.backend.report.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.report.SeedFundReportFileService
import com.terraformation.backend.report.SeedFundReportNotCompleteException
import com.terraformation.backend.report.SeedFundReportService
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.report.model.SeedFundReportFileModel
import com.terraformation.backend.report.model.SeedFundReportMetadata
import com.terraformation.backend.report.model.SeedFundReportPhotoModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import java.nio.file.NoSuchFileException
import java.time.Instant
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@CustomerEndpoint
@RequestMapping("/api/v1/reports")
@RestController
class ReportsController(
    private val seedFundReportFileService: SeedFundReportFileService,
    private val seedFundReportService: SeedFundReportService,
    private val seedFundReportStore: SeedFundReportStore,
    private val userStore: UserStore,
) {
  @GetMapping
  @Operation(summary = "Lists an organization's reports.")
  fun listReports(@RequestParam organizationId: OrganizationId): ListReportsResponsePayload {
    val names = mutableMapOf<UserId, String?>()

    val reports =
        seedFundReportStore.fetchMetadataByOrganization(organizationId).map { metadata ->
          ListReportsResponseElement(metadata) { userId ->
            names.getOrPut(userId) { userStore.fetchFullNameById(userId) }
          }
        }

    return ListReportsResponsePayload(reports)
  }

  @GetMapping("/{id}")
  @Operation(summary = "Retrieves the contents of a report.")
  fun getReport(@PathVariable("id") id: SeedFundReportId): GetReportResponsePayload {
    val model = seedFundReportService.fetchOneById(id)
    val reportPayload = GetReportPayload.of(model, userStore::fetchFullNameById)

    return GetReportResponsePayload(reportPayload)
  }

  @ApiResponse200("The report is now locked by the current user.")
  @ApiResponse409("The report was already locked by another user.")
  @Operation(
      summary = "Locks a report.",
      description =
          "Only succeeds if the report is not currently locked or if it is locked by the " +
              "current user.",
  )
  @PostMapping("/{id}/lock")
  fun lockReport(@PathVariable("id") id: SeedFundReportId): SimpleSuccessResponsePayload {
    seedFundReportStore.lock(id)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200("The report is now locked by the current user.")
  @Operation(summary = "Locks a report even if it is locked by another user already.")
  @PostMapping("/{id}/lock/force")
  fun forceLockReport(@PathVariable("id") id: SeedFundReportId): SimpleSuccessResponsePayload {
    seedFundReportStore.lock(id, true)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200("The report is no longer locked.")
  @ApiResponse409("The report is locked by another user.")
  @Operation(summary = "Releases the lock on a report.")
  @PostMapping("/{id}/unlock")
  fun unlockReport(@PathVariable("id") id: SeedFundReportId): SimpleSuccessResponsePayload {
    seedFundReportStore.unlock(id)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse409("The report is not locked by the current user.")
  @Operation(
      summary = "Updates a report.",
      description = "The report must be locked by the current user.",
  )
  @PutMapping("/{id}")
  fun updateReport(
      @PathVariable("id") id: SeedFundReportId,
      @RequestBody payload: PutReportRequestPayload,
  ): SimpleSuccessResponsePayload {
    seedFundReportService.update(id) { payload.report.copyTo(it) }

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400("The report is missing required information and can't be submitted.")
  @ApiResponse409("The report is not locked by the current user or has already been submitted.")
  @Operation(
      summary = "Submits a report.",
      description =
          "The report must be locked by the current user. Submitting a report releases the lock. " +
              "Once a report is submitted, it may no longer be locked or updated.",
  )
  @PostMapping("/{id}/submit")
  fun submitReport(@PathVariable("id") id: SeedFundReportId): SimpleSuccessResponsePayload {
    try {
      seedFundReportStore.submit(id)
    } catch (e: SeedFundReportNotCompleteException) {
      throw BadRequestException(e.message)
    }

    return SimpleSuccessResponsePayload()
  }

  @GetMapping("/{id}/photos")
  @Operation(summary = "Lists the photos associated with a report.")
  fun listReportPhotos(@PathVariable("id") id: SeedFundReportId): ListReportPhotosResponsePayload {
    val photos = seedFundReportFileService.listPhotos(id)

    return ListReportPhotosResponsePayload(photos.map { ListReportPhotosResponseElement(it) })
  }

  @ApiResponse200Photo
  @GetMapping("/{reportId}/photos/{photoId}")
  @Operation(summary = "Gets the contents of a photo.", description = PHOTO_OPERATION_DESCRIPTION)
  fun getReportPhoto(
      @PathVariable("reportId") reportId: SeedFundReportId,
      @PathVariable("photoId") photoId: FileId,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return try {
      seedFundReportFileService.readPhoto(reportId, photoId, maxWidth, maxHeight).toResponseEntity()
    } catch (e: NoSuchFileException) {
      throw NotFoundException()
    }
  }

  @Operation(summary = "Updates a photo's caption.")
  @PutMapping("/{reportId}/photos/{photoId}")
  fun updateReportPhoto(
      @PathVariable("reportId") reportId: SeedFundReportId,
      @PathVariable("photoId") photoId: FileId,
      @RequestBody payload: UpdateReportPhotoRequestPayload,
  ): SimpleSuccessResponsePayload {
    val existing = seedFundReportFileService.getPhotoModel(reportId, photoId)
    val model = payload.applyTo(existing)

    seedFundReportFileService.updatePhoto(model)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Uploads a photo to include with a report.")
  @PostMapping("/{reportId}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBodyPhotoFile
  fun uploadReportPhoto(
      @PathVariable("reportId") reportId: SeedFundReportId,
      @RequestPart("file") file: MultipartFile,
  ): UploadReportFileResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val filename = file.getFilename()

    val fileId =
        seedFundReportFileService.storePhoto(
            reportId,
            file.inputStream,
            FileMetadata.of(contentType, filename, file.size),
        )

    return UploadReportFileResponsePayload(fileId)
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Deletes a photo from a report.")
  @DeleteMapping("/{reportId}/photos/{photoId}")
  fun deleteReportPhoto(
      @PathVariable("reportId") reportId: SeedFundReportId,
      @PathVariable("photoId") photoId: FileId,
  ): SimpleSuccessResponsePayload {
    seedFundReportFileService.deletePhoto(reportId, photoId)

    return SimpleSuccessResponsePayload()
  }

  @GetMapping("/{id}/files")
  @Operation(summary = "Lists the files associated with a report.")
  fun listReportFiles(@PathVariable("id") id: SeedFundReportId): ListReportFilesResponsePayload {
    val files = seedFundReportFileService.listFiles(id)

    return ListReportFilesResponsePayload(files.map { ListReportFilesResponseElement(it) })
  }

  @ApiResponse(
      responseCode = "200",
      description = "The file was successfully retrieved.",
      content =
          [
              Content(
                  schema = Schema(type = "string", format = "binary"),
                  mediaType = MediaType.ALL_VALUE,
              )
          ],
  )
  @GetMapping("{reportId}/files/{fileId}")
  @Operation(summary = "Downloads a file associated with a report.")
  @Produces
  fun downloadReportFile(
      @PathVariable("reportId") reportId: SeedFundReportId,
      @PathVariable("fileId") fileId: FileId,
  ): ResponseEntity<InputStreamResource> {
    return try {
      val model = seedFundReportFileService.getFileModel(reportId, fileId)

      seedFundReportFileService.readFile(reportId, fileId).toResponseEntity {
        contentDisposition =
            ContentDisposition.attachment().filename(model.metadata.filenameWithoutPath).build()
      }
    } catch (e: NoSuchFileException) {
      throw NotFoundException()
    }
  }

  @Operation(summary = "Uploads a file to associate with a report.")
  @PostMapping("/{reportId}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = MediaType.ALL_VALUE)])]
  )
  fun uploadReportFile(
      @PathVariable("reportId") reportId: SeedFundReportId,
      @RequestPart("file") file: MultipartFile,
  ): UploadReportFileResponsePayload {
    val contentType = file.getPlainContentType() ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
    val filename = file.getFilename()

    val fileId =
        seedFundReportFileService.storeFile(
            reportId,
            file.inputStream,
            FileMetadata.of(contentType, filename, file.size),
        )

    return UploadReportFileResponsePayload(fileId)
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Deletes a file from a report.")
  @DeleteMapping("/{reportId}/files/{fileId}")
  fun deleteReportFile(
      @PathVariable("reportId") reportId: SeedFundReportId,
      @PathVariable("fileId") fileId: FileId,
  ): SimpleSuccessResponsePayload {
    seedFundReportFileService.deleteFile(reportId, fileId)

    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ListReportsResponseElement(
    override val id: SeedFundReportId,
    override val lockedByName: String?,
    override val lockedByUserId: UserId?,
    override val lockedTime: Instant?,
    override val modifiedByName: String?,
    override val modifiedByUserId: UserId?,
    override val modifiedTime: Instant?,
    override val projectId: ProjectId?,
    override val projectName: String?,
    override val quarter: Int,
    override val status: SeedFundReportStatus,
    override val submittedByName: String?,
    override val submittedByUserId: UserId?,
    override val submittedTime: Instant?,
    override val year: Int,
) : ReportMetadataFields {
  constructor(
      metadata: SeedFundReportMetadata,
      getFullName: (UserId) -> String?,
  ) : this(
      id = metadata.id,
      lockedByName = metadata.lockedBy?.let { getFullName(it) },
      lockedByUserId = metadata.lockedBy,
      lockedTime = metadata.lockedTime,
      modifiedByName = metadata.modifiedBy?.let { getFullName(it) },
      modifiedByUserId = metadata.modifiedBy,
      modifiedTime = metadata.modifiedTime,
      projectId = metadata.projectId,
      projectName = metadata.projectName,
      quarter = metadata.quarter,
      status = metadata.status,
      submittedByName = metadata.submittedBy?.let { getFullName(it) },
      submittedByUserId = metadata.submittedBy,
      submittedTime = metadata.submittedTime,
      year = metadata.year,
  )
}

data class ListReportPhotosResponseElement(
    val caption: String?,
    val filename: String,
    val id: FileId,
) {
  constructor(
      model: SeedFundReportPhotoModel
  ) : this(model.caption, model.metadata.filename, model.metadata.id)
}

data class ListReportFilesResponseElement(
    val filename: String,
    val id: FileId,
) {
  constructor(model: SeedFundReportFileModel) : this(model.metadata.filename, model.metadata.id)
}

data class GetReportResponsePayload(
    val report: GetReportPayload,
) : SuccessResponsePayload

data class ListReportsResponsePayload(
    val reports: List<ListReportsResponseElement>,
) : SuccessResponsePayload

data class PutReportRequestPayload(
    val report: PutReportPayload,
)

data class ListReportPhotosResponsePayload(
    val photos: List<ListReportPhotosResponseElement>,
) : SuccessResponsePayload

data class UpdateReportPhotoRequestPayload(val caption: String?) {
  fun applyTo(model: SeedFundReportPhotoModel) = model.copy(caption = caption)
}

data class UploadReportFileResponsePayload(val id: FileId) : SuccessResponsePayload

data class ListReportFilesResponsePayload(
    val files: List<ListReportFilesResponseElement>,
) : SuccessResponsePayload
