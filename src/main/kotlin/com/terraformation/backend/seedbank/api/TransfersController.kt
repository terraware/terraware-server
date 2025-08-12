package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.nursery.api.BatchPayload
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.seedbank.AccessionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import java.time.LocalDate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v2/seedbank/accessions/{accessionId}/transfers")
@RestController
@SeedBankAppEndpoint
class TransfersController(
    private val accessionService: AccessionService,
) {
  @Operation(summary = "Transfers seeds to a nursery.")
  @PostMapping("/nursery")
  fun createNurseryTransferWithdrawal(
      @PathVariable("accessionId") accessionId: AccessionId,
      @RequestBody payload: CreateNurseryTransferRequestPayload
  ): CreateNurseryTransferResponsePayload {
    val (accession, batch) =
        accessionService.createNurseryTransfer(
            accessionId, payload.toNewBatchModel(), payload.withdrawnByUserId)
    return CreateNurseryTransferResponsePayload(AccessionPayloadV2(accession), BatchPayload(batch))
  }
}

data class CreateNurseryTransferRequestPayload(
    val date: LocalDate,
    val destinationFacilityId: FacilityId,
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(0) //
    val germinatingQuantity: Int,
    @Min(0) //
    val hardeningOffQuantity: Int? = 0,
    val notes: String? = null,
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(0) //
    val notReadyQuantity: Int,
    val readyByDate: LocalDate? = null,
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(0) //
    val readyQuantity: Int,
    @Schema(
        description =
            "ID of the user who withdrew the seeds. Default is the current user's ID. If " +
                "non-null, the current user must have permission to read the referenced user's " +
                "membership details in the organization.")
    val withdrawnByUserId: UserId? = null,
) {
  fun toNewBatchModel() =
      NewBatchModel(
          addedDate = date,
          facilityId = destinationFacilityId,
          germinatingQuantity = germinatingQuantity,
          notes = notes,
          notReadyQuantity = notReadyQuantity,
          readyByDate = readyByDate,
          readyQuantity = readyQuantity,
          hardeningOffQuantity = hardeningOffQuantity ?: 0,
          speciesId = null,
      )
}

data class CreateNurseryTransferResponsePayload(
    @Schema(description = "Updated accession that includes a withdrawal for the nursery transfer.")
    val accession: AccessionPayloadV2,
    @Schema(description = "Details of newly-created seedling batch.") //
    val batch: BatchPayload,
) : SuccessResponsePayload
