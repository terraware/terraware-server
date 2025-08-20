package com.terraformation.backend.funder.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.funder.FundingEntityService
import com.terraformation.backend.funder.db.FundingEntityNotFoundException
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.db.FundingEntityUserStore
import com.terraformation.backend.funder.model.FunderUserModel
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.funder.model.FundingProjectModel
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.ws.rs.BadRequestException
import java.time.Instant
import org.apache.commons.validator.routines.EmailValidator
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
    private val fundingEntityStore: FundingEntityStore,
    private val fundingEntityUserStore: FundingEntityUserStore,
) {
  private val emailValidator = EmailValidator.getInstance()

  @ApiResponse200
  @GetMapping
  @Operation(summary = "Lists all funding entities.")
  fun listFundingEntities(): ListFundingEntitiesPayload {
    val elements = fundingEntityStore.fetchAll().map { model -> FundingEntityPayload(model) }
    return ListFundingEntitiesPayload(elements)
  }

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
      @RequestBody @Valid payload: UpdateFundingEntityRequestPayload,
  ): SimpleSuccessResponsePayload {
    fundingEntityService.update(payload.toRow(fundingEntityId), payload.projects)
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

  @Operation(summary = "List funders for a Funding Entity")
  @GetMapping("/{fundingEntityId}/users")
  fun getFunders(
      @PathVariable fundingEntityId: FundingEntityId,
  ): GetFundersResponsePayload {
    val funders = fundingEntityUserStore.fetchFundersForEntity(fundingEntityId)

    return GetFundersResponsePayload(funders.map { FunderPayload(it) })
  }

  @Operation(summary = "Invites a funder via email to a Funding Entity")
  @PostMapping("/{fundingEntityId}/users")
  fun inviteFunder(
      @PathVariable fundingEntityId: FundingEntityId,
      @RequestBody payload: InviteFundingEntityFunderRequestPayload,
  ): InviteFundingEntityFunderResponsePayload {
    if (!emailValidator.isValid(payload.email)) {
      throw BadRequestException("Field value has incorrect format: email")
    }

    fundingEntityService.inviteFunder(fundingEntityId, payload.email)

    return InviteFundingEntityFunderResponsePayload(payload.email)
  }

  @Operation(summary = "Removes a funder from a Funding Entity")
  @DeleteMapping("/{fundingEntityId}/users")
  fun removeFunder(
      @PathVariable fundingEntityId: FundingEntityId,
      @RequestBody payload: DeleteFundersRequestPayload,
  ): SimpleSuccessResponsePayload {
    fundingEntityService.deleteFunders(fundingEntityId, payload.userIds)
    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Gets the Funding Entity that a specific user belongs to")
  @GetMapping("/users/{userId}")
  fun getFundingEntity(@PathVariable userId: UserId): GetFundingEntityResponsePayload {
    val model =
        fundingEntityUserStore.fetchEntityByUserId(userId)
            ?: throw FundingEntityNotFoundException(userId)
    return GetFundingEntityResponsePayload(fundingEntity = FundingEntityPayload(model))
  }

  @Operation(summary = "Gets the Funding Entities that a specific project is tied to")
  @GetMapping("/projects/{projectId}")
  fun getProjectFundingEntities(@PathVariable projectId: ProjectId): ListFundingEntitiesPayload {
    val elements =
        fundingEntityStore.fetchByProjectId(projectId).map { model -> FundingEntityPayload(model) }
    return ListFundingEntitiesPayload(elements)
  }
}

data class FundingEntityPayload(
    val id: FundingEntityId,
    val name: String,
    val projects: List<FundingProjectPayload>,
) {
  constructor(
      model: FundingEntityModel
  ) : this(
      id = model.id,
      name = model.name,
      projects = model.projects.map { FundingProjectPayload(it) },
  )
}

data class GetFundingEntityResponsePayload(val fundingEntity: FundingEntityPayload) :
    SuccessResponsePayload

data class CreateFundingEntityRequestPayload(
    val name: String,
    val projects: Set<ProjectId>? = null,
)

data class UpdateFundingEntityRequestPayload(
    val name: String,
    val projects: Set<ProjectId>? = null,
) {
  fun toRow(id: FundingEntityId?): FundingEntitiesRow {
    return FundingEntitiesRow(id, name)
  }
}

data class FunderPayload(
    val userId: UserId,
    val accountCreated: Boolean,
    val createdTime: Instant,
    val email: String,
    val firstName: String?,
    val lastName: String?,
) {
  constructor(
      user: FunderUserModel
  ) : this(
      userId = user.userId,
      accountCreated = user.accountCreated,
      createdTime = user.createdTime,
      email = user.email,
      firstName = user.firstName,
      lastName = user.lastName,
  )
}

data class FundingProjectPayload(
    val projectId: ProjectId,
    val dealName: String,
) {
  constructor(
      model: FundingProjectModel
  ) : this(
      projectId = model.projectId,
      dealName = model.dealName,
  )
}

data class InviteFundingEntityFunderRequestPayload(val email: String)

data class DeleteFundersRequestPayload(val userIds: Set<UserId>)

data class GetFundersResponsePayload(val funders: List<FunderPayload>) : SuccessResponsePayload

data class InviteFundingEntityFunderResponsePayload(val email: String) : SuccessResponsePayload

data class ListFundingEntitiesPayload(val fundingEntities: List<FundingEntityPayload>) :
    SuccessResponsePayload
