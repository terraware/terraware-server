package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ReportService
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.core.io.InputStreamResource
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
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/reports")
@RestController
class AcceleratorReportsController(
    private val reportService: ReportService,
    private val reportStore: ReportStore,
) {
  @ApiResponse200
  @GetMapping("/{reportId}")
  @Operation(summary = "Get one report.")
  fun getOneAcceleratorReport(
      @PathVariable reportId: ReportId,
      @RequestParam includeIndicators: Boolean? = null,
  ): GetAcceleratorReportResponsePayload {
    val model =
        reportService.fetchOne(
            reportId = reportId,
            includeIndicators = includeIndicators ?: false,
            computeUnpublishedChanges = true,
        )
    return GetAcceleratorReportResponsePayload(AcceleratorReportPayload(model))
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}")
  @Operation(summary = "Update indicator data and qualitative data for a report")
  fun updateOneAcceleratorReportValues(
      @PathVariable reportId: ReportId,
      @RequestBody payload: UpdateAcceleratorReportValuesRequestPayload,
  ): SimpleSuccessResponsePayload {
    val commonIndicatorUpdates =
        when {
          (payload.commonIndicators != null) ->
              payload.commonIndicators.associate { it.id to it.toModel() }
          else -> {
            throw IllegalArgumentException("Requires commonIndicators to be specified")
          }
        }
    val autoCalculatedIndicatorUpdates =
        when {
          (payload.autoCalculatedIndicators != null) ->
              payload.autoCalculatedIndicators.associate { it.indicator to it.toModel() }
          else -> {
            throw IllegalArgumentException("Requires autoCalculatedIndicators to be specified")
          }
        }
    val projectIndicatorUpdates =
        when {
          (payload.projectIndicators != null) ->
              payload.projectIndicators.associate { it.id to it.toModel() }
          else -> {
            throw IllegalArgumentException("Requires projectIndicators to be specified")
          }
        }

    reportStore.updateReport(
        reportId = reportId,
        highlights = payload.highlights,
        achievements = payload.achievements,
        challenges = payload.challenges.map { it.toModel() },
        financialSummaries = payload.financialSummaries,
        additionalComments = payload.additionalComments,
        commonIndicatorEntries = commonIndicatorUpdates,
        autoCalculatedIndicatorEntries = autoCalculatedIndicatorUpdates,
        projectIndicatorEntries = projectIndicatorUpdates,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/indicators/refresh")
  @Operation(summary = "Refresh auto calculated indicator entries value for a report")
  fun refreshOneAcceleratorReportAutoCalculatedIndicators(
      @PathVariable reportId: ReportId,
      @RequestParam indicators: List<AutoCalculatedIndicator>,
  ): SimpleSuccessResponsePayload {
    reportStore.refreshAutoCalculatedIndicatorValues(reportId, indicators)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/review")
  @Operation(summary = "Review a report")
  fun reviewOneAcceleratorReport(
      @PathVariable reportId: ReportId,
      @RequestBody payload: ReviewAcceleratorReportRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportStore.reviewReport(
        reportId = reportId,
        status = payload.review.status,
        highlights = payload.review.highlights,
        achievements = payload.review.achievements,
        challenges = payload.review.challenges.map { it.toModel() },
        financialSummaries = payload.review.financialSummaries,
        additionalComments = payload.review.additionalComments,
        feedback = payload.review.feedback,
        internalComment = payload.review.internalComment,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/indicators/review")
  @Operation(summary = "Review indicator entries for a report")
  fun reviewOneAcceleratorReportIndicators(
      @PathVariable reportId: ReportId,
      @RequestBody payload: ReviewAcceleratorReportIndicatorsRequestPayload,
  ): SimpleSuccessResponsePayload {
    val commonIndicatorUpdates = payload.commonIndicators.associate { it.id to it.toModel() }
    val autoCalculatedIndicatorUpdates =
        payload.autoCalculatedIndicators.associate { it.indicator to it.toModel() }
    val projectIndicatorUpdates = payload.projectIndicators.associate { it.id to it.toModel() }

    reportStore.reviewReportIndicators(
        reportId = reportId,
        commonIndicatorEntries = commonIndicatorUpdates,
        autoCalculatedIndicatorEntries = autoCalculatedIndicatorUpdates,
        projectIndicatorEntries = projectIndicatorUpdates,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/submit")
  @Operation(summary = "Submits a report for review")
  fun submitOneAcceleratorReport(
      @PathVariable reportId: ReportId,
  ): SimpleSuccessResponsePayload {
    reportStore.submitReport(reportId)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @PostMapping("/{reportId}/publish")
  @Operation(summary = "Publishes a report to funder")
  fun publishOneAcceleratorReport(
      @PathVariable reportId: ReportId,
  ): SimpleSuccessResponsePayload {
    reportService.publishReport(reportId)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Deletes a report photo")
  @DeleteMapping("/{reportId}/photos/{fileId}")
  @RequestBodyPhotoFile
  fun deleteOneAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @PathVariable fileId: FileId,
  ): SimpleSuccessResponsePayload {
    reportService.deleteReportPhoto(
        reportId = reportId,
        fileId = fileId,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200Photo
  @ApiResponse404("The report does not exist, or does not have a photo with the requested ID.")
  @GetMapping(
      "/{reportId}/photos/{fileId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE],
  )
  @Operation(
      summary = "Retrieves a specific photo from a report",
      description = PHOTO_OPERATION_DESCRIPTION,
  )
  @ResponseBody
  fun getOneAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @PathVariable fileId: FileId,
      @Parameter(description = PHOTO_MAXWIDTH_DESCRIPTION) @RequestParam maxWidth: Int? = null,
      @Parameter(description = PHOTO_MAXHEIGHT_DESCRIPTION) @RequestParam maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return reportService.readReportPhoto(reportId, fileId, maxWidth, maxHeight).toResponseEntity()
  }

  @Operation(summary = "Updates a report photo caption")
  @PutMapping("/{reportId}/photos/{fileId}")
  @RequestBodyPhotoFile
  fun updateOneAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @PathVariable fileId: FileId,
      @RequestBody payload: UpdateAcceleratorReportPhotoRequestPayload,
  ): SimpleSuccessResponsePayload {
    reportService.updateReportPhotoCaption(
        caption = payload.caption,
        reportId = reportId,
        fileId = fileId,
    )

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Uploads a photo to a report.")
  @PostMapping(
      "/{reportId}/photos",
      consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
  )
  @RequestBodyPhotoFile
  fun uploadOneAcceleratorReportPhoto(
      @PathVariable reportId: ReportId,
      @RequestPart file: MultipartFile,
      @RequestPart(required = false) caption: String?,
  ): UploadAcceleratorReportPhotoResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val filename = file.getFilename("photo")

    val fileId =
        reportService.storeReportPhoto(
            caption = caption,
            data = file.inputStream,
            metadata = FileMetadata.of(contentType, filename, file.size),
            reportId = reportId,
        )

    return UploadAcceleratorReportPhotoResponsePayload(fileId)
  }
}
