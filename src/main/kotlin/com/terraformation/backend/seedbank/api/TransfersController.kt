package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.BatchId
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
      @PathVariable accessionId: AccessionId,
      @RequestBody payload: CreateNurseryTransferRequestPayload,
  ): CreateNurseryTransferResponsePayload {
    val (accession, batch) =
        accessionService.createNurseryTransfer(
            accessionId = accessionId,
            batch = payload.toNewBatchModel(),
            batchId = payload.batchId,
            withdrawnByUserId = payload.withdrawnByUserId,
        )
    return CreateNurseryTransferResponsePayload(AccessionPayloadV2(accession), BatchPayload(batch))
  }
}

data class CreateNurseryTransferRequestPayload(
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonAlias("notReadyQuantity")
    @Min(0)
    @Schema(description = "Ignored if batchId is specified.")
    val activeGrowthQuantity: Int,
    @Schema(
        description =
            "If this transfer should add to an existing batch, the batch's ID. Default is to " +
                "create a new batch. The batch must be at the facility specified by " +
                "destinationFacilityId, and it must be of the same species as the accession."
    )
    val batchId: BatchId?,
    val date: LocalDate,
    val destinationFacilityId: FacilityId,
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(0) //
    val germinatingQuantity: Int,
    @Min(0) //
    @Schema(description = "Ignored if batchId is specified.")
    val hardeningOffQuantity: Int? = 0,
    val notes: String? = null,
    val readyByDate: LocalDate? = null,
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(0) //
    @Schema(description = "Ignored if batchId is specified.")
    val readyQuantity: Int,
    @Schema(
        description =
            "ID of the user who withdrew the seeds. Default is the current user's ID. If " +
                "non-null, the current user must have permission to read the referenced user's " +
                "membership details in the organization."
    )
    val withdrawnByUserId: UserId? = null,
) {
  fun toNewBatchModel() =
      NewBatchModel(
          addedDate = date,
          activeGrowthQuantity = activeGrowthQuantity,
          facilityId = destinationFacilityId,
          germinatingQuantity = germinatingQuantity,
          notes = notes,
          readyByDate = readyByDate,
          readyQuantity = readyQuantity,
          hardeningOffQuantity = hardeningOffQuantity ?: 0,
          speciesId = null,
      )
}

data class CreateNurseryTransferResponsePayload(
    @Schema(description = "Updated accession that includes a withdrawal for the nursery transfer.")
    val accession: AccessionPayloadV2,
    @Schema(description = "Details of the seedling batch the seeds were added to.")
    val batch: BatchPayload,
) : SuccessResponsePayload
