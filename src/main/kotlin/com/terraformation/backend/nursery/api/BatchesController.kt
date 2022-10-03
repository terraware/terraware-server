package com.terraformation.backend.nursery.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.nursery.db.BatchStore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@NurseryEndpoint
@RestController
@RequestMapping("/api/v1/nursery/batches")
class BatchesController(
    private val batchStore: BatchStore,
) {
  @GetMapping("/{id}")
  fun getBatch(@PathVariable("id") id: BatchId): BatchResponsePayload {
    val row = batchStore.fetchOneById(id)
    return BatchResponsePayload(BatchPayload(row))
  }

  @PostMapping
  fun createBatch(@RequestBody payload: CreateBatchRequestPayload): BatchResponsePayload {
    val insertedRow = batchStore.create(payload.toRow())
    return BatchResponsePayload(BatchPayload(insertedRow))
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
    val germinatingQuantity: Int,
    val notes: String? = null,
    val notReadyQuantity: Int,
    val readyByDate: LocalDate? = null,
    val readyQuantity: Int,
    val speciesId: SpeciesId,
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

data class BatchResponsePayload(val batch: BatchPayload) : SuccessResponsePayload
