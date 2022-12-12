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
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.nursery.db.BatchStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.validation.constraints.Min
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
  fun getBatch(@PathVariable("id") id: BatchId): BatchResponsePayload {
    val row = batchStore.fetchOneById(id)
    return BatchResponsePayload(BatchPayload(row))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The batch was created successfully. Response includes fields populated by the " +
              "server, including the batch ID.")
  @PostMapping
  fun createBatch(@RequestBody payload: CreateBatchRequestPayload): BatchResponsePayload {
    val insertedRow = batchStore.create(payload.toRow())
    return BatchResponsePayload(BatchPayload(insertedRow))
  }

  @ApiResponseSimpleSuccess
  @DeleteMapping("/{id}")
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
    batchStore.updateDetails(
        batchId = id,
        version = payload.version,
        notes = payload.notes,
        readyByDate = payload.readyByDate)

    return getBatch(id)
  }

  @ApiResponse200
  @ApiResponse404
  @ApiResponse412
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
    val id: BatchId,
    val latestObservedTime: Instant,
    val notes: String?,
    val notReadyQuantity: Int,
    val readyByDate: LocalDate?,
    val readyQuantity: Int,
    val speciesId: SpeciesId,
    @Schema(
        description =
            "Increases every time a batch is updated. Must be passed as a parameter for certain " +
                "kinds of write operations to detect when a batch has changed since the client " +
                "last retrieved it.")
    val version: Int,
) {
  constructor(
      row: BatchesRow
  ) : this(
      accessionId = row.accessionId,
      addedDate = row.addedDate!!,
      batchNumber = row.batchNumber!!,
      facilityId = row.facilityId!!,
      germinatingQuantity = row.germinatingQuantity!!,
      id = row.id!!,
      latestObservedTime = row.latestObservedTime!!.truncatedTo(ChronoUnit.SECONDS),
      notes = row.notes,
      notReadyQuantity = row.notReadyQuantity!!,
      readyByDate = row.readyByDate,
      readyQuantity = row.readyQuantity!!,
      speciesId = row.speciesId!!,
      version = row.version!!,
  )
}

data class CreateBatchRequestPayload(
    val addedDate: LocalDate,
    val facilityId: FacilityId,
    val notes: String? = null,
    val readyByDate: LocalDate? = null,
    val speciesId: SpeciesId,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val germinatingQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val notReadyQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val readyQuantity: Int,
) {
  fun toRow() =
      BatchesRow(
          addedDate = addedDate,
          facilityId = facilityId,
          germinatingQuantity = germinatingQuantity,
          notes = notes,
          notReadyQuantity = notReadyQuantity,
          readyByDate = readyByDate,
          readyQuantity = readyQuantity,
          speciesId = speciesId,
      )
}

data class UpdateBatchRequestPayload(
    val notes: String?,
    val readyByDate: LocalDate?,
    @JsonSetter(nulls = Nulls.FAIL) val version: Int,
)

data class UpdateBatchQuantitiesRequestPayload(
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val germinatingQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val notReadyQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) @Min(0) val readyQuantity: Int,
    @JsonSetter(nulls = Nulls.FAIL) val version: Int,
)

data class BatchResponsePayload(val batch: BatchPayload) : SuccessResponsePayload
