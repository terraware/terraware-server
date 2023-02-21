package com.terraformation.backend.report.api

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
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.report.ReportNotCompleteException
import com.terraformation.backend.report.ReportPhotoService
import com.terraformation.backend.report.ReportService
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportPhotoModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.nio.file.NoSuchFileException
import java.time.Instant
import javax.ws.rs.BadRequestException
import javax.ws.rs.NotFoundException
import javax.ws.rs.QueryParam
import org.springframework.core.io.InputStreamResource
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
    private val reportPhotoService: ReportPhotoService,
    private val reportService: ReportService,
    private val reportStore: ReportStore,
    private val userStore: UserStore,
) {
  @GetMapping
  @Operation(summary = "Lists an organization's reports.")
  fun listReports(
      @RequestParam(required = true) organizationId: OrganizationId
  ): ListReportsResponsePayload {
    val reports =
        reportStore.fetchMetadataByOrganization(organizationId).map { metadata ->
          val lockedByName =
              metadata.lockedBy?.let { (userStore.fetchOneById(it) as? IndividualUser)?.fullName }
          ListReportsResponseElement(metadata, lockedByName)
        }

    return ListReportsResponsePayload(reports)
  }

  @GetMapping("/{id}")
  @Operation(summary = "Retrieves the contents of a report.")
  fun getReport(@PathVariable("id") id: ReportId): GetReportResponsePayload {
    val model = reportService.fetchOneById(id)
    val lockedByName =
        model.metadata.lockedBy?.let { (userStore.fetchOneById(it) as? IndividualUser)?.fullName }
    val reportPayload = GetReportPayload.of(model, lockedByName)

    return GetReportResponsePayload(reportPayload)
  }

  @ApiResponse200("The report is now locked by the current user.")
  @ApiResponse409("The report was already locked by another user.")
  @Operation(
      summary = "Locks a report.",
      description =
          "Only succeeds if the report is not currently locked or if it is locked by the " +
              "current user.")
  @PostMapping("/{id}/lock")
  fun lockReport(@PathVariable("id") id: ReportId): SimpleSuccessResponsePayload {
    reportStore.lock(id)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200("The report is now locked by the current user.")
  @Operation(summary = "Locks a report even if it is locked by another user already.")
  @PostMapping("/{id}/lock/force")
  fun forceLockReport(@PathVariable("id") id: ReportId): SimpleSuccessResponsePayload {
    reportStore.lock(id, true)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200("The report is no longer locked.")
  @ApiResponse409("The report is locked by another user.")
  @Operation(summary = "Releases the lock on a report.")
  @PostMapping("/{id}/unlock")
  fun unlockReport(@PathVariable("id") id: ReportId): SimpleSuccessResponsePayload {
    reportStore.unlock(id)

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
      @PathVariable("id") id: ReportId,
      @RequestBody payload: PutReportRequestPayload
  ): SimpleSuccessResponsePayload {
    reportService.update(id) { payload.report.copyTo(it) }

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400("The report is missing required information and can't be submitted.")
  @ApiResponse409("The report is not locked by the current user or has already been submitted.")
  @Operation(
      summary = "Submits a report.",
      description =
          "The report must be locked by the current user. Submitting a report releases the lock. " +
              "Once a report is submitted, it may no longer be locked or updated.")
  @PostMapping("/{id}/submit")
  fun submitReport(@PathVariable("id") id: ReportId): SimpleSuccessResponsePayload {
    try {
      reportStore.submit(id)
    } catch (e: ReportNotCompleteException) {
      throw BadRequestException(e.message)
    }

    return SimpleSuccessResponsePayload()
  }

  @GetMapping("/{id}/photos")
  @Operation(summary = "Lists the photos associated with a report.")
  fun listReportPhotos(@PathVariable("id") id: ReportId): ListReportPhotosResponsePayload {
    val photos = reportPhotoService.listPhotos(id)

    return ListReportPhotosResponsePayload(photos.map { ListReportPhotosResponseElement(it) })
  }

  @ApiResponse200Photo
  @GetMapping("/{reportId}/photos/{photoId}")
  @Operation(summary = "Gets the contents of a photo.", description = PHOTO_OPERATION_DESCRIPTION)
  fun getReportPhoto(
      @PathVariable("reportId") reportId: ReportId,
      @PathVariable("photoId") photoId: PhotoId,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return try {
      reportPhotoService.readPhoto(reportId, photoId).toResponseEntity()
    } catch (e: NoSuchFileException) {
      throw NotFoundException()
    }
  }

  @Operation(summary = "Updates a photo's caption.")
  @PutMapping("/{reportId}/photos/{photoId}")
  fun updateReportPhoto(
      @PathVariable("reportId") reportId: ReportId,
      @PathVariable("photoId") photoId: PhotoId,
      @RequestBody payload: UpdateReportPhotoRequestPayload
  ): SimpleSuccessResponsePayload {
    val model = payload.toModel(reportId, photoId)

    reportPhotoService.updatePhoto(model)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Uploads a photo to include with a report.")
  @PostMapping("/{reportId}/photos")
  @RequestBodyPhotoFile
  fun uploadReportPhoto(
      @PathVariable("reportId") reportId: ReportId,
      @RequestPart("file") file: MultipartFile
  ): UploadReportPhotoResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val filename = file.getFilename()

    val photoId =
        reportPhotoService.storePhoto(
            reportId, file.inputStream, PhotoMetadata(filename, contentType, file.size))

    return UploadReportPhotoResponsePayload(photoId)
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Deletes a photo from a report.")
  @DeleteMapping("/{reportId}/photos/{photoId}")
  fun deleteReportPhoto(
      @PathVariable("reportId") reportId: ReportId,
      @PathVariable("photoId") photoId: PhotoId
  ): SimpleSuccessResponsePayload {
    reportPhotoService.deletePhoto(reportId, photoId)

    return SimpleSuccessResponsePayload()
  }
}

data class ListReportsResponseElement(
    override val id: ReportId,
    override val lockedByName: String?,
    override val lockedByUserId: UserId?,
    override val lockedTime: Instant?,
    override val quarter: Int,
    override val status: ReportStatus,
    override val year: Int
) : ReportMetadataFields {
  constructor(
      metadata: ReportMetadata,
      lockedByName: String?
  ) : this(
      id = metadata.id,
      lockedByName = lockedByName,
      lockedByUserId = metadata.lockedBy,
      lockedTime = metadata.lockedTime,
      quarter = metadata.quarter,
      status = metadata.status,
      year = metadata.year,
  )
}

data class ListReportPhotosResponseElement(
    val caption: String?,
    val id: PhotoId,
) {
  constructor(model: ReportPhotoModel) : this(model.caption, model.photoId)
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
  fun toModel(reportId: ReportId, photoId: PhotoId) = ReportPhotoModel(caption, photoId, reportId)
}

data class UploadReportPhotoResponsePayload(val photoId: PhotoId) : SuccessResponsePayload
