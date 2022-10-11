package com.terraformation.backend.nursery.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import javax.validation.constraints.Min
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@NurseryEndpoint
@RequestMapping("/api/v1/nursery/withdrawals")
@RestController
class WithdrawalsController(
    val batchStore: BatchStore,
) {
  @PostMapping
  fun createBatchWithdrawal(
      @RequestBody payload: CreateNurseryWithdrawalRequestPayload
  ): CreateNurseryWithdrawalResponsePayload {
    val model = batchStore.withdraw(payload.toModel())
    val batchModels = model.batchWithdrawals.map { batchStore.fetchOneById(it.batchId) }

    return CreateNurseryWithdrawalResponsePayload(
        batchModels.map { BatchPayload(it) },
        NurseryWithdrawalPayload(model),
    )
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
    val destination: String? = null,
    val facilityId: FacilityId,
    val id: WithdrawalId,
    val purpose: WithdrawalPurpose,
    val reason: String?,
    val withdrawnDate: LocalDate,
) {
  constructor(
      model: ExistingWithdrawalModel
  ) : this(
      model.batchWithdrawals.map { BatchWithdrawalPayload(it) },
      model.destination,
      model.facilityId,
      model.id,
      model.purpose,
      model.reason,
      model.withdrawnDate,
  )
}

data class CreateNurseryWithdrawalRequestPayload(
    @ArraySchema(minItems = 1) val batchWithdrawals: List<BatchWithdrawalPayload>,
    val destination: String? = null,
    val facilityId: FacilityId,
    val purpose: WithdrawalPurpose,
    val reason: String? = null,
    val withdrawnDate: LocalDate,
) {
  fun toModel() =
      NewWithdrawalModel(
          batchWithdrawals = batchWithdrawals.map { it.toModel() },
          destination = destination,
          facilityId = facilityId,
          id = null,
          purpose = purpose,
          reason = reason,
          withdrawnDate = withdrawnDate,
      )
}

data class CreateNurseryWithdrawalResponsePayload(
    val batches: List<BatchPayload>,
    val withdrawal: NurseryWithdrawalPayload
) : SuccessResponsePayload
