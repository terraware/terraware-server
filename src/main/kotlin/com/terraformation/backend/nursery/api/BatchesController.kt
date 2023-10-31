package com.terraformation.backend.nursery.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse412
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.NewBatchModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.Min
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@NurseryEndpoint
@RestController
@RequestMapping("/api/v1/nursery/batches")
class BatchesController(
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
    batchStore.changeStatuses(
        id, payload.germinatingQuantityToChange, payload.notReadyQuantityToChange)

    return getBatch(id)
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BatchPayload(
    @Schema(
        description =
            "If this batch was created via a seed withdrawal, the ID of the seed accession it " +
                "came from.")
    val accessionId: AccessionId?,
    val addedDate: LocalDate,
    val batchNumber: String,
    val facilityId: FacilityId,
    val germinatingQuantity: Int,
    val germinationRate: Int?,
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
    val speciesId: SpeciesId,
    val subLocationIds: Set<SubLocationId>,
    val substrate: BatchSubstrate?,
    val substrateNotes: String?,
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
      addedDate = model.addedDate,
      batchNumber = model.batchNumber,
      facilityId = model.facilityId,
      germinatingQuantity = model.germinatingQuantity,
      germinationRate = model.germinationRate,
      id = model.id,
      initialBatchId = model.initialBatchId,
      latestObservedTime = model.latestObservedTime.truncatedTo(ChronoUnit.SECONDS),
      lossRate = model.lossRate,
      notes = model.notes,
      notReadyQuantity = model.notReadyQuantity,
      projectId = model.projectId,
      readyByDate = model.readyByDate,
      readyQuantity = model.readyQuantity,
      speciesId = model.speciesId,
      subLocationIds = model.subLocationIds,
      substrate = model.substrate,
      substrateNotes = model.substrateNotes,
      treatment = model.treatment,
      treatmentNotes = model.treatmentNotes,
      version = model.version,
  )
}

data class CreateBatchRequestPayload(
    val addedDate: LocalDate,
    val facilityId: FacilityId,
    val notes: String? = null,
    val projectId: ProjectId? = null,
    val readyByDate: LocalDate? = null,
    val speciesId: SpeciesId,
    val subLocationIds: Set<SubLocationId>? = null,
    val substrate: BatchSubstrate? = null,
    val substrateNotes: String? = null,
    val treatment: SeedTreatment? = null,
    val treatmentNotes: String? = null,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val germinatingQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val notReadyQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val readyQuantity: Int,
) {
  fun toModel() =
      NewBatchModel(
          addedDate = addedDate,
          facilityId = facilityId,
          germinatingQuantity = germinatingQuantity,
          notes = notes,
          notReadyQuantity = notReadyQuantity,
          projectId = projectId,
          readyByDate = readyByDate,
          readyQuantity = readyQuantity,
          speciesId = speciesId,
          subLocationIds = subLocationIds ?: emptySet(),
          substrate = substrate,
          substrateNotes = substrateNotes,
          treatment = treatment,
          treatmentNotes = treatmentNotes,
      )
}

data class UpdateBatchRequestPayload(
    val notes: String?,
    val projectId: ProjectId?,
    val readyByDate: LocalDate?,
    val subLocationIds: Set<SubLocationId>? = null,
    val substrate: BatchSubstrate? = null,
    val substrateNotes: String? = null,
    val treatment: SeedTreatment? = null,
    val treatmentNotes: String? = null,
    @JsonSetter(nulls = Nulls.FAIL) val version: Int,
) {
  fun applyChanges(model: ExistingBatchModel): ExistingBatchModel {
    return model.copy(
        notes = notes,
        projectId = projectId,
        readyByDate = readyByDate,
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
) {
  val germinatingQuantityToChange
    get() =
        if (operation == ChangeBatchStatusOperation.GerminatingToNotReady) {
          quantity
        } else {
          0
        }
  val notReadyQuantityToChange
    get() =
        if (operation == ChangeBatchStatusOperation.NotReadyToReady) {
          quantity
        } else {
          0
        }
}

data class BatchResponsePayload(val batch: BatchPayload) : SuccessResponsePayload
