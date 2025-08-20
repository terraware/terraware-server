package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.db.WithdrawalStore
import com.terraformation.backend.seedbank.model.WithdrawalModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v2/seedbank/accessions/{accessionId}/withdrawals")
@RestController
@SeedBankAppEndpoint
class WithdrawalsController(
    private val accessionService: AccessionService,
    private val withdrawalStore: WithdrawalStore,
) {
  @GetMapping
  @Operation(summary = "List all the withdrawals from an accession.")
  fun listWithdrawals(
      @PathVariable("accessionId") accessionId: AccessionId
  ): GetWithdrawalsResponsePayload {
    val withdrawals = withdrawalStore.fetchWithdrawals(accessionId)
    val payloads = withdrawals.map { GetWithdrawalPayload(it) }

    return GetWithdrawalsResponsePayload(payloads)
  }

  @GetMapping("/{withdrawalId}")
  @Operation(summary = "Get a single withdrawal.")
  fun getWithdrawal(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
  ): GetWithdrawalResponsePayload {
    val model = withdrawalStore.fetchOneById(withdrawalId)
    return GetWithdrawalResponsePayload(GetWithdrawalPayload(model))
  }

  @Operation(
      summary = "Create a new withdrawal on an existing accession.",
      description = "May cause the accession's remaining quantity to change.",
  )
  @PostMapping
  fun createWithdrawal(
      @PathVariable("accessionId") accessionId: AccessionId,
      @RequestBody payload: CreateWithdrawalRequestPayload,
  ): UpdateAccessionResponsePayloadV2 {
    val accession = accessionService.createWithdrawal(payload.toModel(accessionId))
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }

  @Operation(
      summary = "Update the details of an existing withdrawal.",
      description = "May cause the accession's remaining quantity to change.",
  )
  @PutMapping("/{withdrawalId}")
  fun updateWithdrawal(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
      @RequestBody payload: UpdateWithdrawalRequestPayload,
  ): UpdateAccessionResponsePayloadV2 {
    val accession =
        accessionService.updateWithdrawal(accessionId, withdrawalId, payload::applyToModel)
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }

  @DeleteMapping("/{withdrawalId}")
  @Operation(
      summary = "Delete an existing withdrawal.",
      description = "May cause the accession's remaining quantity to change.",
  )
  fun deleteWithdrawal(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("withdrawalId") withdrawalId: WithdrawalId,
  ): UpdateAccessionResponsePayloadV2 {
    val accession = accessionService.deleteWithdrawal(accessionId, withdrawalId)
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetWithdrawalPayload(
    val date: LocalDate,
    @Schema(
        description =
            "Number of seeds withdrawn. Calculated by server. This is an estimate if " +
                "\"withdrawnQuantity\" is a weight quantity and the accession has subset weight " +
                "and count data. Absent if \"withdrawnQuantity\" is a weight quantity and the " +
                "accession has no subset weight and count."
    )
    val estimatedCount: Int? = null,
    @Schema(
        description =
            "Weight of seeds withdrawn. Calculated by server. This is an estimate if " +
                "\"withdrawnQuantity\" is a seed count and the accession has subset weight and " +
                "count data. Absent if \"withdrawnQuantity\" is a seed count and the accession " +
                "has no subset weight and count."
    )
    val estimatedWeight: SeedQuantityPayload? = null,
    @Schema(description = "Server-assigned unique ID of this withdrawal.")
    val id: WithdrawalId? = null,
    val purpose: WithdrawalPurpose? = null,
    val notes: String? = null,
    @Schema(
        description =
            "If this withdrawal is of purpose \"Viability Testing\", the ID of the test it is " +
                "associated with."
    )
    val viabilityTestId: ViabilityTestId? = null,
    @Schema(
        description =
            "Full name of the person who withdrew the seeds. " +
                "V1 COMPATIBILITY: This is the \"staffResponsible\" v1 field, which may not be " +
                "the name of an organization user."
    )
    val withdrawnByName: String? = null,
    @Schema(
        description =
            "ID of the user who withdrew the seeds. Only present if the current user has " +
                "permission to list the users in the organization. " +
                "V1 COMPATIBILITY: Also absent if the withdrawal was written with the v1 API " +
                "and we haven't yet written the code to figure out which user ID to assign."
    )
    val withdrawnByUserId: UserId? = null,
    @Schema(
        description =
            "Quantity of seeds withdrawn. For viability testing withdrawals, this is always the " +
                "same as the test's \"seedsTested\" value."
    )
    val withdrawnQuantity: SeedQuantityPayload? = null,
) {
  constructor(
      model: WithdrawalModel
  ) : this(
      date = model.date,
      estimatedCount = model.estimatedCount,
      estimatedWeight = model.estimatedWeight?.toPayload(),
      id = model.id,
      purpose = model.purpose,
      notes = model.notes,
      viabilityTestId = model.viabilityTestId,
      withdrawnByName = model.withdrawnByName,
      withdrawnByUserId = model.withdrawnByUserId,
      withdrawnQuantity = model.withdrawn?.toPayload(),
  )
}

// Mark all fields as write-only in the schema
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateWithdrawalRequestPayload(
    val date: LocalDate,
    val purpose: WithdrawalPurpose? = null,
    val notes: String? = null,
    @Schema(
        description =
            "ID of the user who withdrew the seeds. Default is the current user's ID. If " +
                "non-null, the current user must have permission to read the referenced user's " +
                "membership details in the organization."
    )
    val withdrawnByUserId: UserId? = null,
    @Schema(
        description =
            "Quantity of seeds withdrawn. If this quantity is in weight and the remaining " +
                "quantity of the accession is in seeds or vice versa, the accession must have a " +
                "subset weight and count."
    )
    val withdrawnQuantity: SeedQuantityPayload,
) {
  fun toModel(accessionId: AccessionId): WithdrawalModel =
      WithdrawalModel(
          accessionId = accessionId,
          date = date,
          notes = notes,
          purpose = purpose,
          withdrawn = withdrawnQuantity.toModel(),
      )
}

// Mark all fields as write-only in the schema
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class UpdateWithdrawalRequestPayload(
    val date: LocalDate,
    val purpose: WithdrawalPurpose? = null,
    val notes: String? = null,
    @Schema(
        description =
            "ID of the user who withdrew the seeds. Default is the withdrawal's existing user " +
                "ID. If non-null, the current user must have permission to read the referenced " +
                "user's membership details in the organization."
    )
    val withdrawnByUserId: UserId? = null,
    @Schema(
        description =
            "Quantity of seeds withdrawn. For viability testing withdrawals, this is always " +
                "the same as the test's \"seedsTested\" value. Otherwise, it is a user-supplied " +
                "value. If this quantity is in weight and the remaining quantity of the " +
                "accession is in seeds or vice versa, the accession must have a subset weight " +
                "and count."
    )
    val withdrawnQuantity: SeedQuantityPayload? = null,
) {
  fun applyToModel(model: WithdrawalModel): WithdrawalModel =
      model.copy(
          date = date,
          purpose = purpose,
          notes = notes,
          withdrawn = withdrawnQuantity?.toModel(),
          withdrawnByUserId = withdrawnByUserId ?: model.withdrawnByUserId,
      )
}

data class GetWithdrawalResponsePayload(val withdrawal: GetWithdrawalPayload) :
    SuccessResponsePayload

data class GetWithdrawalsResponsePayload(val withdrawals: List<GetWithdrawalPayload>) :
    SuccessResponsePayload
