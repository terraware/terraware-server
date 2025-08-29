package com.terraformation.backend.nursery.api

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
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
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.nursery.BatchService
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.db.NurseryWithdrawalStore
import com.terraformation.backend.nursery.db.WithdrawalPhotoService
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.nursery.model.NurserySpeciesModel
import com.terraformation.backend.nursery.model.PlotSpeciesModel
import com.terraformation.backend.tracking.api.DeliveryPayload
import com.terraformation.backend.tracking.db.DeliveryStore
import com.terraformation.backend.tracking.model.DeliveryModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.Min
import jakarta.ws.rs.QueryParam
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.core.io.InputStreamResource
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
    val batchService: BatchService,
    val batchStore: BatchStore,
    val deliveryStore: DeliveryStore,
    val nurseryWithdrawalStore: NurseryWithdrawalStore,
    val withdrawalPhotoService: WithdrawalPhotoService,
) {
  @GetMapping("/{withdrawalId}")
  @Operation(summary = "Gets information about a specific nursery withdrawal.")
  fun getNurseryWithdrawal(
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId
  ): GetNurseryWithdrawalResponsePayload {
    val withdrawal = batchStore.fetchWithdrawalById(withdrawalId)
    val batches = withdrawal.batchWithdrawals.map { batchStore.fetchOneById(it.batchId) }
    val deliveryModel =
        if (withdrawal.purpose == WithdrawalPurpose.OutPlant) {
          deliveryStore.fetchOneByWithdrawalId(withdrawalId)
        } else {
          null
        }

    return GetNurseryWithdrawalResponsePayload(batches, deliveryModel, withdrawal)
  }

  @GetMapping("/plantingSite/{plantingSiteId}/species")
  @Operation(summary = "Lists all the species that have been withdrawn to a planting site.")
  fun getSpeciesWithdrawnToPlantingSite(
      @PathVariable("plantingSiteId") plantingSiteId: PlantingSiteId
  ): GetSitePlotSpeciesResponsePayload {
    val plots = nurseryWithdrawalStore.fetchSiteSpeciesByPlot(plantingSiteId)

    return GetSitePlotSpeciesResponsePayload(plots.map { PlotSpeciesDensitiesPayload(it) })
  }

  @Operation(summary = "Withdraws seedlings from one or more seedling batches at a nursery.")
  @PostMapping
  fun createBatchWithdrawal(
      @RequestBody payload: CreateNurseryWithdrawalRequestPayload
  ): GetNurseryWithdrawalResponsePayload {
    val withdrawal =
        batchService.withdraw(
            payload.toModel(),
            payload.readyByDate,
            payload.plantingSiteId,
            payload.plantingSubzoneId,
        )
    val batches = withdrawal.batchWithdrawals.map { batchStore.fetchOneById(it.batchId) }
    val deliveryModel = withdrawal.deliveryId?.let { deliveryStore.fetchOneById(it) }

    return GetNurseryWithdrawalResponsePayload(batches, deliveryModel, withdrawal)
  }

  @Operation(summary = "Creates a new photo of a seedling batch withdrawal.")
  @PostMapping("/{withdrawalId}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBodyPhotoFile
  fun uploadWithdrawalPhoto(
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
      @RequestPart("file") file: MultipartFile,
  ): CreateNurseryWithdrawalPhotoResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val filename = file.getFilename("photo")

    val fileId =
        withdrawalPhotoService.storePhoto(
            withdrawalId,
            file.inputStream,
            FileMetadata.of(contentType, filename, file.size),
        )

    return CreateNurseryWithdrawalPhotoResponsePayload(fileId)
  }

  @ApiResponse200Photo
  @ApiResponse404("The withdrawal does not exist, or does not have a photo with the requested ID.")
  @GetMapping(
      "/{withdrawalId}/photos/{photoId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE],
  )
  @Operation(
      summary = "Retrieves a specific photo from a withdrawal.",
      description = PHOTO_OPERATION_DESCRIPTION,
  )
  @ResponseBody
  fun getWithdrawalPhoto(
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
      @PathVariable("photoId") photoId: FileId,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return withdrawalPhotoService
        .readPhoto(withdrawalId, photoId, maxWidth, maxHeight)
        .toResponseEntity()
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404("The withdrawal does not exist.")
  @GetMapping("/{withdrawalId}/photos")
  @Operation(summary = "Lists all the photos of a withdrawal.")
  fun listWithdrawalPhotos(
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
  ): ListWithdrawalPhotosResponsePayload {
    val fileIds = withdrawalPhotoService.listPhotos(withdrawalId)

    return ListWithdrawalPhotosResponsePayload(fileIds.map { NurseryWithdrawalPhotoPayload(it) })
  }

  @ApiResponse200
  @ApiResponse404("The withdrawal does not exist.")
  @ApiResponse409(
      "The withdrawal is not eligible for undo, e.g., because it has already been undone or " +
          "because it is a nursery transfer."
  )
  @PostMapping("/{withdrawalId}/undo")
  @Operation(
      summary = "Undoes a withdrawal.",
      description =
          "The withdrawal's plants will be returned to their original batches. Nursery transfers " +
              "may not be undone. If the withdrawal was an outplanting to a planting site, the " +
              "plants will be removed from the planting site's plant totals. This does not " +
              "delete the original withdrawal.",
  )
  fun undoBatchWithdrawal(@PathVariable withdrawalId: WithdrawalId): SimpleSuccessResponsePayload {
    batchService.undoWithdrawal(withdrawalId)

    return SimpleSuccessResponsePayload()
  }
}

data class BatchWithdrawalPayload(
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(0)
    @JsonAlias("notReadyQuantityWithdrawn")
    val activeGrowthQuantityWithdrawn: Int,
    val batchId: BatchId,
    @Schema(defaultValue = "0") @Min(0) val germinatingQuantityWithdrawn: Int? = null,
    @Schema(defaultValue = "0") @Min(0) val hardeningOffQuantityWithdrawn: Int? = null,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val readyQuantityWithdrawn: Int,
) {
  constructor(
      model: BatchWithdrawalModel
  ) : this(
      activeGrowthQuantityWithdrawn = model.activeGrowthQuantityWithdrawn,
      batchId = model.batchId,
      germinatingQuantityWithdrawn = model.germinatingQuantityWithdrawn,
      hardeningOffQuantityWithdrawn = model.hardeningOffQuantityWithdrawn,
      readyQuantityWithdrawn = model.readyQuantityWithdrawn,
  )

  fun toModel() =
      BatchWithdrawalModel(
          batchId = batchId,
          germinatingQuantityWithdrawn = germinatingQuantityWithdrawn ?: 0,
          activeGrowthQuantityWithdrawn = activeGrowthQuantityWithdrawn,
          hardeningOffQuantityWithdrawn = hardeningOffQuantityWithdrawn ?: 0,
          readyQuantityWithdrawn = readyQuantityWithdrawn,
      )

  val notReadyQuantityWithdrawn:
      Int? // for backwards compatibility in response payloads. This is optional so that request
    // payloads don't require it
    get() = activeGrowthQuantityWithdrawn
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NurseryWithdrawalPayload(
    val batchWithdrawals: List<BatchWithdrawalPayload>,
    @Schema(
        description =
            "If purpose is \"Nursery Transfer\", the ID of the facility to which the seedlings " +
                "were transferred."
    )
    val destinationFacilityId: FacilityId? = null,
    val facilityId: FacilityId,
    val id: WithdrawalId,
    val notes: String?,
    val purpose: WithdrawalPurpose,
    val withdrawnDate: LocalDate,
    @Schema(description = "If purpose is \"Undo\", the ID of the withdrawal this one undoes.")
    val undoesWithdrawalId: WithdrawalId?,
    @Schema(description = "If this withdrawal was undone, the ID of the withdrawal that undid it.")
    val undoneByWithdrawalId: WithdrawalId?,
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
      model.undoesWithdrawalId,
      model.undoneByWithdrawalId,
  )
}

/** Valid user-supplied withdrawal purposes. */
enum class CreateWithdrawalPurpose(val purpose: WithdrawalPurpose) {
  NurseryTransfer(WithdrawalPurpose.NurseryTransfer),
  Dead(WithdrawalPurpose.Dead),
  OutPlant(WithdrawalPurpose.OutPlant),
  Other(WithdrawalPurpose.Other);

  val jsonValue: String
    @JsonValue get() = purpose.jsonValue

  companion object {
    private val byJsonValue = entries.associateBy { it.purpose.jsonValue }

    @JsonCreator
    @JvmStatic
    fun forJsonValue(jsonValue: String): CreateWithdrawalPurpose {
      return byJsonValue[jsonValue]
          ?: throw IllegalArgumentException("Unrecognized value: $jsonValue")
    }
  }
}

data class CreateNurseryWithdrawalRequestPayload(
    @ArraySchema(minItems = 1) val batchWithdrawals: List<BatchWithdrawalPayload>,
    @Schema(
        description =
            "If purpose is \"Nursery Transfer\", the ID of the facility to transfer to. Must be " +
                "in the same organization as the originating facility. Not allowed for purposes " +
                "other than \"Nursery Transfer\"."
    )
    val destinationFacilityId: FacilityId? = null,
    val facilityId: FacilityId,
    val notes: String? = null,
    @Schema(
        description =
            "If purpose is \"Out Plant\", the ID of the planting site to which the seedlings " +
                "were delivered."
    )
    val plantingSiteId: PlantingSiteId?,
    @Schema(
        description =
            "If purpose is \"Out Plant\", the ID of the planting subzone to which the seedlings " +
                "were delivered. Must be specified if the planting site has planting subzones, " +
                "but must be omitted or set to null if the planting site has no planting subzones."
    )
    val plantingSubzoneId: PlantingSubzoneId?,
    val purpose: CreateWithdrawalPurpose,
    @Schema(
        description =
            "If purpose is \"Nursery Transfer\", the estimated ready-by date to use for the " +
                "batches that are created at the other nursery."
    )
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
          purpose = purpose.purpose,
          withdrawnDate = withdrawnDate,
      )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetNurseryWithdrawalResponsePayload(
    val batches: List<BatchPayload>,
    @Schema(
        description =
            "If the withdrawal was an outplanting to a planting site, the delivery that was " +
                "created. Not present for other withdrawal purposes."
    )
    val delivery: DeliveryPayload?,
    val withdrawal: NurseryWithdrawalPayload,
) : SuccessResponsePayload {
  constructor(
      batches: List<ExistingBatchModel>,
      delivery: DeliveryModel?,
      withdrawal: ExistingWithdrawalModel,
  ) : this(
      batches.map { BatchPayload(it) },
      delivery?.let { DeliveryPayload(it) },
      NurseryWithdrawalPayload(withdrawal),
  )
}

data class SpeciesDensityPayload(
    val speciesId: SpeciesId,
    @Schema(description = "Species density in plants per hectare.") val density: BigDecimal,
) {
  constructor(
      model: NurserySpeciesModel
  ) : this(
      speciesId = model.speciesId,
      density = model.density,
  )
}

data class PlotSpeciesDensitiesPayload(
    val monitoringPlotId: MonitoringPlotId,
    val species: List<SpeciesDensityPayload>,
) {
  constructor(
      model: PlotSpeciesModel
  ) : this(
      monitoringPlotId = model.monitoringPlotId,
      species = model.species.map { SpeciesDensityPayload(it) },
  )
}

data class GetSitePlotSpeciesResponsePayload(val plots: List<PlotSpeciesDensitiesPayload>) :
    SuccessResponsePayload

data class CreateNurseryWithdrawalPhotoResponsePayload(val id: FileId) : SuccessResponsePayload

data class NurseryWithdrawalPhotoPayload(val id: FileId)

data class ListWithdrawalPhotosResponsePayload(val photos: List<NurseryWithdrawalPhotoPayload>) :
    SuccessResponsePayload
