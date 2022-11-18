package com.terraformation.backend.nursery.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.db.WithdrawalPhotoService
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.LocalDate
import javax.validation.constraints.Min
import javax.ws.rs.NotSupportedException
import javax.ws.rs.QueryParam
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@NurseryEndpoint
@RequestMapping("/api/v1/nursery/withdrawals")
@RestController
class WithdrawalsController(
    val batchStore: BatchStore,
    val withdrawalPhotoService: WithdrawalPhotoService,
) {
  @PostMapping
  fun createBatchWithdrawal(
      @RequestBody payload: CreateNurseryWithdrawalRequestPayload
  ): CreateNurseryWithdrawalResponsePayload {
    val model = batchStore.withdraw(payload.toModel(), payload.readyByDate)
    val batchModels = model.batchWithdrawals.map { batchStore.fetchOneById(it.batchId) }

    return CreateNurseryWithdrawalResponsePayload(
        batchModels.map { BatchPayload(it) },
        NurseryWithdrawalPayload(model),
    )
  }

  @Operation(summary = "Creates a new photo of a seedling batch withdrawal.")
  @PostMapping("/{withdrawalId}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          [
              Content(
                  encoding =
                      [
                          Encoding(
                              name = "file",
                              contentType =
                                  "${MediaType.IMAGE_JPEG_VALUE}, ${MediaType.IMAGE_PNG_VALUE}")])])
  fun uploadWithdrawalPhoto(
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
      @RequestPart("file") file: MultipartFile,
  ): CreateNurseryWithdrawalPhotoResponsePayload {
    val extensions = mapOf(MediaType.IMAGE_JPEG_VALUE to "jpg", MediaType.IMAGE_PNG_VALUE to "png")
    val contentType = file.contentType?.substringBefore(';')
    if (contentType == null || contentType !in extensions) {
      throw NotSupportedException("Photos must be of type image/jpeg or image/png")
    }

    val filename = file.originalFilename ?: "photo.${extensions[contentType]}"

    val photoId =
        withdrawalPhotoService.storePhoto(
            withdrawalId,
            file.inputStream,
            file.size,
            PhotoMetadata(filename, contentType, file.size))

    return CreateNurseryWithdrawalPhotoResponsePayload(photoId)
  }

  @ApiResponse(
      responseCode = "200",
      description = "The photo was successfully retrieved.",
      content =
          [
              Content(
                  schema = Schema(type = "string", format = "binary"),
                  mediaType = MediaType.IMAGE_JPEG_VALUE),
              Content(
                  schema = Schema(type = "string", format = "binary"),
                  mediaType = MediaType.IMAGE_PNG_VALUE)])
  @ApiResponse404("The withdrawal does not exist, or does not have a photo with the requested ID.")
  @GetMapping(
      "/{withdrawalId}/photos/{photoId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE])
  @Operation(
      summary = "Retrieves a specific photo from a withdrawal.",
      description = PHOTO_OPERATION_DESCRIPTION)
  @ResponseBody
  fun getWithdrawalPhoto(
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
      @PathVariable("photoId") photoId: PhotoId,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    val headers = HttpHeaders()

    val inputStream = withdrawalPhotoService.readPhoto(withdrawalId, photoId, maxWidth, maxHeight)
    headers.contentLength = inputStream.size
    headers.contentType = inputStream.contentType

    val resource = InputStreamResource(inputStream)
    return ResponseEntity(resource, headers, HttpStatus.OK)
  }

  @ApiResponse404("The withdrawal does not exist.")
  @GetMapping("/{withdrawalId}/photos")
  @Operation(summary = "Lists all the photos of a withdrawal.")
  fun listWithdrawalPhotos(
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
  ): ListWithdrawalPhotosResponsePayload {
    val photoIds = withdrawalPhotoService.listPhotos(withdrawalId)

    return ListWithdrawalPhotosResponsePayload(photoIds.map { NurseryWithdrawalPhotoPayload(it) })
  }
}

data class BatchWithdrawalPayload(
    val batchId: BatchId,
    @Schema(defaultValue = "0") @Min(0) val germinatingQuantityWithdrawn: Int? = null,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val notReadyQuantityWithdrawn: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val readyQuantityWithdrawn: Int,
) {
  constructor(
      model: BatchWithdrawalModel
  ) : this(
      batchId = model.batchId,
      germinatingQuantityWithdrawn = model.germinatingQuantityWithdrawn,
      notReadyQuantityWithdrawn = model.notReadyQuantityWithdrawn,
      readyQuantityWithdrawn = model.readyQuantityWithdrawn,
  )

  fun toModel() =
      BatchWithdrawalModel(
          batchId = batchId,
          germinatingQuantityWithdrawn = germinatingQuantityWithdrawn ?: 0,
          notReadyQuantityWithdrawn = notReadyQuantityWithdrawn,
          readyQuantityWithdrawn = readyQuantityWithdrawn,
      )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NurseryWithdrawalPayload(
    val batchWithdrawals: List<BatchWithdrawalPayload>,
    @Schema(
        description =
            "If purpose is \"Nursery Transfer\", the ID of the facility to which the seedlings " +
                "were transferred.")
    val destinationFacilityId: FacilityId? = null,
    val facilityId: FacilityId,
    val id: WithdrawalId,
    val notes: String?,
    val purpose: WithdrawalPurpose,
    val withdrawnDate: LocalDate,
) {
  constructor(
      model: ExistingWithdrawalModel
  ) : this(
      model.batchWithdrawals.map { BatchWithdrawalPayload(it) },
      model.destinationFacilityId,
      model.facilityId,
      model.id,
      model.notes,
      model.purpose,
      model.withdrawnDate,
  )
}

data class CreateNurseryWithdrawalRequestPayload(
    @ArraySchema(minItems = 1) val batchWithdrawals: List<BatchWithdrawalPayload>,
    @Schema(
        description =
            "If purpose is \"Nursery Transfer\", the ID of the facility to transfer to. Must be " +
                "in the same organization as the originating facility. Not allowed for purposes " +
                "other than \"Nursery Transfer\".")
    val destinationFacilityId: FacilityId? = null,
    val facilityId: FacilityId,
    val notes: String? = null,
    val purpose: WithdrawalPurpose,
    @Schema(
        description =
            "If purpose is \"Nursery Transfer\", the estimated ready-by date to use for the " +
                "batches that are created at the other nursery.")
    val readyByDate: LocalDate? = null,
    val withdrawnDate: LocalDate,
) {
  fun toModel() =
      NewWithdrawalModel(
          batchWithdrawals = batchWithdrawals.map { it.toModel() },
          destinationFacilityId = destinationFacilityId,
          facilityId = facilityId,
          id = null,
          notes = notes,
          purpose = purpose,
          withdrawnDate = withdrawnDate,
      )
}

data class CreateNurseryWithdrawalResponsePayload(
    val batches: List<BatchPayload>,
    val withdrawal: NurseryWithdrawalPayload
) : SuccessResponsePayload

data class CreateNurseryWithdrawalPhotoResponsePayload(val id: PhotoId) : SuccessResponsePayload

data class NurseryWithdrawalPhotoPayload(val id: PhotoId)

data class ListWithdrawalPhotosResponsePayload(val photos: List<NurseryWithdrawalPhotoPayload>) :
    SuccessResponsePayload
