package com.terraformation.backend.funder.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.model.FundingEntityModel
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@FunderEndpoint
@RequestMapping("/api/v1/funder/entities")
@RestController
class FundingEntitiesController(private val fundingEntityStore: FundingEntityStore) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{fundingEntityId}")
  @Operation(summary = "Gets information about a Funding Entity")
  fun getFundingEntity(
      @PathVariable fundingEntityId: FundingEntityId
  ): GetFundingEntityResponsePayload {
    val model = fundingEntityStore.fetchOneById(fundingEntityId)
    return GetFundingEntityResponsePayload(
        FundingEntityPayload(model),
    )
  }

  @Operation(summary = "Creates a new Funding entity")
  @PostMapping
  fun createFundingEntity(
      @RequestBody @Valid payload: CreateFundingEntityRequestPayload
  ): GetFundingEntityResponsePayload {
    val model = fundingEntityStore.create(payload.name)

    return GetFundingEntityResponsePayload(FundingEntityPayload(model))
  }
}

data class FundingEntityPayload(
    val id: FundingEntityId,
    val name: String,
) {
  constructor(model: FundingEntityModel) : this(id = model.id, name = model.name)
}

data class GetFundingEntityResponsePayload(val fundingEntity: FundingEntityPayload) :
    SuccessResponsePayload

data class CreateFundingEntityRequestPayload(val name: String) {}
