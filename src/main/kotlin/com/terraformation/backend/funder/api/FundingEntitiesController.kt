package com.terraformation.backend.funder.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.funder.FundingEntityService
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.model.FundingEntityModel
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@FunderEndpoint
@RequestMapping("/api/v1/funder/entities")
@RestController
class FundingEntitiesController(
    private val fundingEntityService: FundingEntityService,
    private val fundingEntityStore: FundingEntityStore
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{fundingEntityId}")
  @Operation(summary = "Gets information about a funding entity")
  fun getFundingEntity(
      @PathVariable fundingEntityId: FundingEntityId,
  ): GetFundingEntityResponsePayload {
    val model = fundingEntityStore.fetchOneById(fundingEntityId)
    return GetFundingEntityResponsePayload(FundingEntityPayload(model))
  }

  @Operation(summary = "Creates a new funding entity")
  @PostMapping
  fun createFundingEntity(
      @RequestBody @Valid payload: CreateFundingEntityRequestPayload
  ): GetFundingEntityResponsePayload {
    val model = fundingEntityService.create(payload.name, payload.projects)

    return GetFundingEntityResponsePayload(FundingEntityPayload(model))
  }

  @Operation(summary = "Updates an existing funding entity")
  @PutMapping("/{fundingEntityId}")
  fun updateFundingEntity(
      @PathVariable("fundingEntityId") fundingEntityId: FundingEntityId,
      @RequestBody @Valid payload: UpdateFundingEntityRequestPayload
  ): SimpleSuccessResponsePayload {
    fundingEntityService.update(
        payload.toRow().copy(id = fundingEntityId), payload.addProjects, payload.removeProjects)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Deletes an existing funding entity",
  )
  @DeleteMapping("/{fundingEntityId}")
  fun deleteFundingEntity(
      @PathVariable("fundingEntityId") fundingEntityId: FundingEntityId
  ): SimpleSuccessResponsePayload {
    fundingEntityService.deleteFundingEntity(fundingEntityId)
    return SimpleSuccessResponsePayload()
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

data class CreateFundingEntityRequestPayload(
    val name: String,
    val projects: Set<ProjectId>? = null,
)

data class UpdateFundingEntityRequestPayload(
    val name: String,
    val addProjects: Set<ProjectId>? = null,
    val removeProjects: Set<ProjectId>? = null,
) {
  fun toRow(): FundingEntitiesRow {
    return FundingEntitiesRow(name = name)
  }
}
