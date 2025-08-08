package com.terraformation.backend.nursery.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse412
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.nursery.db.BatchPhotoService
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.nursery.model.NurseryBatchPhase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.Min
import jakarta.ws.rs.QueryParam
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@NurseryEndpoint
@RestController
@RequestMapping("/api/v1/nursery/batches")
class BatchesController(
    private val batchPhotoService: BatchPhotoService,
    private val batchStore: BatchStore,
) {
  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{id}")
  @Operation(summary = "Gets information about a single seedling batch.")
  fun getBatch(@PathVariable("id") id: BatchId): BatchResponsePayload {
    val row = batchStore.fetchOneById(id)
    return BatchResponsePayload(BatchPayload(row))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The batch was created successfully. Response includes fields populated by the " +
              "server, including the batch ID.")
  @Operation(summary = "Creates a new seedling batch at a nursery.")
  @PostMapping
  fun createBatch(@RequestBody payload: CreateBatchRequestPayload): BatchResponsePayload {
    val insertedModel = batchStore.create(payload.toModel())
    return BatchResponsePayload(BatchPayload(insertedModel))
  }

  @ApiResponseSimpleSuccess
  @DeleteMapping("/{id}")
  @Operation(summary = "Deletes an existing seedling batch from a nursery.")
  fun deleteBatch(@PathVariable("id") id: BatchId): SimpleSuccessResponsePayload {
    batchStore.delete(id)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The batch was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.")
  @ApiResponse404
  @ApiResponse412
  @Operation(summary = "Updates non-quantity-related details about a batch.")
  @PutMapping("/{id}")
  fun updateBatch(
      @PathVariable("id") id: BatchId,
      @RequestBody payload: UpdateBatchRequestPayload
  ): BatchResponsePayload {
    batchStore.updateDetails(id, payload.version, payload::applyChanges)

    return getBatch(id)
  }

  @ApiResponse200
  @ApiResponse404
  @ApiResponse412
  @Operation(
      summary = "Updates the remaining quantities in a seedling batch.",
      description =
          "This should not be used to record withdrawals; use the withdrawal API for that.")
  @PutMapping("/{id}/quantities")
  fun updateBatchQuantities(
      @PathVariable("id") id: BatchId,
      @RequestBody payload: UpdateBatchQuantitiesRequestPayload
  ): BatchResponsePayload {
    batchStore.updateQuantities(
        batchId = id,
        version = payload.version,
        germinating = payload.germinatingQuantity,
        notReady = payload.notReadyQuantity,
        hardeningOff = payload.hardeningOffQuantity,
        ready = payload.readyQuantity,
        historyType = BatchQuantityHistoryType.Observed)

    return getBatch(id)
  }

  @ApiResponse200
  @ApiResponse404
  @ApiResponse412
  @Operation(
      summary = "Changes the statuses of seedlings in a batch.",
      description = "There must be enough seedlings available to move to the next status.")
  @PostMapping("/{id}/changeStatuses")
  fun changeBatchStatuses(
      @PathVariable("id") id: BatchId,
      @RequestBody payload: ChangeBatchStatusRequestPayload
  ): BatchResponsePayload {
    val (previousPhase, newPhase) =
        when (payload.operation) {
          ChangeBatchStatusOperation.GerminatingToNotReady ->
              NurseryBatchPhase.Germinating to NurseryBatchPhase.NotReady
          ChangeBatchStatusOperation.NotReadyToReady ->
              NurseryBatchPhase.NotReady to NurseryBatchPhase.Ready
        }

    batchStore.changeStatuses(id, previousPhase, newPhase, payload.quantity)

    return getBatch(id)
  }

  @Operation(summary = "Creates a new photo of a seedling batch.")
  @PostMapping("/{batchId}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBodyPhotoFile
  fun createBatchPhoto(
      @PathVariable batchId: BatchId,
      @RequestPart("file") file: MultipartFile,
  ): CreateBatchPhotoResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val filename = file.getFilename("photo")

    val fileId =
        batchPhotoService.storePhoto(
            batchId, file.inputStream, FileMetadata.of(contentType, filename, file.size))

    return CreateBatchPhotoResponsePayload(fileId)
  }

  @ApiResponse200Photo
  @ApiResponse404("The batch does not exist, or does not have a photo with the requested ID.")
  @GetMapping(
      "/{batchId}/photos/{photoId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE])
  @Operation(
      summary = "Retrieves a specific photo from a seedling batch.",
      description = PHOTO_OPERATION_DESCRIPTION)
  @ResponseBody
  fun getBatchPhoto(
      @PathVariable batchId: BatchId,
      @PathVariable photoId: FileId,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return batchPhotoService.readPhoto(batchId, photoId, maxWidth, maxHeight).toResponseEntity()
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404("The batch does not exist.")
  @GetMapping("/{batchId}/photos")
  @Operation(summary = "Lists all the photos of a seedling batch.")
  fun listBatchPhotos(
      @PathVariable batchId: BatchId,
  ): ListBatchPhotosResponsePayload {
    val fileIds = batchPhotoService.listPhotos(batchId).mapNotNull { it.fileId }

    return ListBatchPhotosResponsePayload(fileIds.map { BatchPhotoPayload(it) })
  }

  @ApiResponse200
  @ApiResponse404("The batch does not exist, or does not have a photo with the requested ID.")
  @DeleteMapping("/{batchId}/photos/{photoId}")
  @Operation(summary = "Deletes a photo from a seedling batch.")
  fun deleteBatchPhoto(
      @PathVariable batchId: BatchId,
      @PathVariable photoId: FileId
  ): SimpleSuccessResponsePayload {
    batchPhotoService.deletePhoto(batchId, photoId)

    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BatchPayload(
    @Schema(
        description =
            "If this batch was created via a seed withdrawal, the ID of the seed accession it " +
                "came from.")
    val accessionId: AccessionId?,
    @Schema(
        description =
            "If this batch was created via a seed withdrawal, the accession number associated to " +
                "the seed accession it came from.")
    val accessionNumber: String?,
    val addedDate: LocalDate,
    val batchNumber: String,
    val facilityId: FacilityId,
    val germinatingQuantity: Int,
    val germinationRate: Int?,
    val germinationStartedDate: LocalDate?,
    val hardeningOffQuantity: Int,
    val id: BatchId,
    @Schema(
        description =
            "If this batch was created via a nursery transfer from another batch, the ID of the " +
                "batch it came from.")
    val initialBatchId: BatchId?,
    val latestObservedTime: Instant,
    val lossRate: Int?,
    val notes: String?,
    val notReadyQuantity: Int,
    val projectId: ProjectId?,
    val readyByDate: LocalDate?,
    val readyQuantity: Int,
    val seedsSownDate: LocalDate?,
    val speciesId: SpeciesId,
    val subLocationIds: Set<SubLocationId>,
    val substrate: BatchSubstrate?,
    val substrateNotes: String?,
    val totalWithdrawn: Int,
    val treatment: SeedTreatment?,
    val treatmentNotes: String?,
    @Schema(
        description =
            "Increases every time a batch is updated. Must be passed as a parameter for certain " +
                "kinds of write operations to detect when a batch has changed since the client " +
                "last retrieved it.")
    val version: Int,
) {
  constructor(
      model: ExistingBatchModel
  ) : this(
      accessionId = model.accessionId,
      accessionNumber = model.accessionNumber,
      addedDate = model.addedDate,
      batchNumber = model.batchNumber,
      facilityId = model.facilityId,
      germinatingQuantity = model.germinatingQuantity,
      germinationRate = model.germinationRate,
      germinationStartedDate = model.germinationStartedDate,
      hardeningOffQuantity = model.hardeningOffQuantity,
      id = model.id,
      initialBatchId = model.initialBatchId,
      latestObservedTime = model.latestObservedTime.truncatedTo(ChronoUnit.SECONDS),
      lossRate = model.lossRate,
      notes = model.notes,
      notReadyQuantity = model.notReadyQuantity,
      projectId = model.projectId,
      readyByDate = model.readyByDate,
      readyQuantity = model.readyQuantity,
      seedsSownDate = model.seedsSownDate,
      speciesId = model.speciesId,
      subLocationIds = model.subLocationIds,
      substrate = model.substrate,
      substrateNotes = model.substrateNotes,
      totalWithdrawn = model.totalWithdrawn,
      treatment = model.treatment,
      treatmentNotes = model.treatmentNotes,
      version = model.version,
  )
}

data class CreateBatchRequestPayload(
    val addedDate: LocalDate,
    val facilityId: FacilityId,
    val germinationStartedDate: LocalDate? = null,
    val notes: String? = null,
    val projectId: ProjectId? = null,
    val readyByDate: LocalDate? = null,
    val seedsSownDate: LocalDate? = null,
    val speciesId: SpeciesId,
    val subLocationIds: Set<SubLocationId>? = null,
    val substrate: BatchSubstrate? = null,
    val substrateNotes: String? = null,
    val treatment: SeedTreatment? = null,
    val treatmentNotes: String? = null,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val germinatingQuantity: Int,
    @Min(0) val hardeningOffQuantity: Int = 0,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val notReadyQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val readyQuantity: Int,
) {
  fun toModel() =
      NewBatchModel(
          addedDate = addedDate,
          facilityId = facilityId,
          germinatingQuantity = germinatingQuantity,
          germinationStartedDate = germinationStartedDate,
          hardeningOffQuantity = hardeningOffQuantity,
          notes = notes,
          notReadyQuantity = notReadyQuantity,
          projectId = projectId,
          readyByDate = readyByDate,
          readyQuantity = readyQuantity,
          seedsSownDate = seedsSownDate,
          speciesId = speciesId,
          subLocationIds = subLocationIds ?: emptySet(),
          substrate = substrate,
          substrateNotes = substrateNotes,
          treatment = treatment,
          treatmentNotes = treatmentNotes,
      )
}

data class UpdateBatchRequestPayload(
    val germinationStartedDate: LocalDate? = null,
    val notes: String?,
    val projectId: ProjectId?,
    val readyByDate: LocalDate?,
    val seedsSownDate: LocalDate? = null,
    val subLocationIds: Set<SubLocationId>? = null,
    val substrate: BatchSubstrate? = null,
    val substrateNotes: String? = null,
    val treatment: SeedTreatment? = null,
    val treatmentNotes: String? = null,
    @JsonSetter(nulls = Nulls.FAIL) val version: Int,
) {
  fun applyChanges(model: ExistingBatchModel): ExistingBatchModel {
    return model.copy(
        germinationStartedDate = germinationStartedDate,
        notes = notes,
        projectId = projectId,
        readyByDate = readyByDate,
        seedsSownDate = seedsSownDate,
        subLocationIds = subLocationIds ?: emptySet(),
        substrate = substrate,
        substrateNotes = substrateNotes,
        treatment = treatment,
        treatmentNotes = treatmentNotes,
    )
  }
}

data class UpdateBatchQuantitiesRequestPayload(
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val germinatingQuantity: Int,
    @Min(0) val hardeningOffQuantity: Int = 0,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val notReadyQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val readyQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) val version: Int,
)

enum class ChangeBatchStatusOperation {
  GerminatingToNotReady,
  NotReadyToReady,
}

data class ChangeBatchStatusRequestPayload(
    @Schema(description = "Which status change to apply.")
    val operation: ChangeBatchStatusOperation,
    @Schema(description = "Number of seedlings to move from one status to the next.")
    val quantity: Int,
)

data class BatchPhotoPayload(val id: FileId)

data class BatchResponsePayload(val batch: BatchPayload) : SuccessResponsePayload

data class CreateBatchPhotoResponsePayload(val id: FileId) : SuccessResponsePayload

data class ListBatchPhotosResponsePayload(val photos: List<BatchPhotoPayload>) :
    SuccessResponsePayload
