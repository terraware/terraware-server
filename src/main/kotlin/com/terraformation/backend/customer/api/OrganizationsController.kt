package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleErrorResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TooManyRequestsException
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.OrganizationService
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.OrganizationUserModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.InvitationTooRecentException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.ws.rs.BadRequestException
import javax.ws.rs.NotFoundException
import org.apache.commons.validator.routines.EmailValidator
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationsController(
    private val clock: Clock,
    private val organizationService: OrganizationService,
    private val organizationStore: OrganizationStore,
) {
  private val emailValidator = EmailValidator.getInstance()

  @GetMapping
  @Operation(
      summary = "Lists all organizations.",
      description = "Lists all organizations the user can access.",
  )
  fun listOrganizations(
      @RequestParam("depth", defaultValue = "Organization")
      @Schema(description = "Return this level of information about the organization's contents.")
      depth: OrganizationStore.FetchDepth,
  ): ListOrganizationsResponsePayload {
    val elements =
        organizationStore.fetchAll(depth).map { model ->
          OrganizationPayload(model, getRole(model))
        }
    return ListOrganizationsResponsePayload(elements)
  }

  @GetMapping("/{organizationId}")
  @Operation(summary = "Gets information about an organization.")
  fun getOrganization(
      @PathVariable("organizationId")
      @Schema(description = "ID of organization to get. User must be a member of the organization.")
      organizationId: OrganizationId,
      @RequestParam("depth", defaultValue = "Organization")
      @Schema(description = "Return this level of information about the organization's contents.")
      depth: OrganizationStore.FetchDepth,
  ): GetOrganizationResponsePayload {
    val model =
        organizationStore.fetchById(organizationId, depth)
            ?: throw OrganizationNotFoundException(organizationId)
    return GetOrganizationResponsePayload(OrganizationPayload(model, getRole(model)))
  }

  @Operation(summary = "Creates a new organization.")
  @PostMapping
  fun createOrganization(
      @RequestBody payload: UpdateOrganizationRequestPayload
  ): GetOrganizationResponsePayload {
    val model = organizationStore.createWithAdmin(payload.toRow())
    return GetOrganizationResponsePayload(OrganizationPayload(model, Role.OWNER))
  }

  @Operation(summary = "Updates an existing organization.")
  @PutMapping("/{organizationId}")
  fun updateOrganization(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @RequestBody payload: UpdateOrganizationRequestPayload
  ): SimpleSuccessResponsePayload {
    organizationStore.update(payload.toRow().copy(id = organizationId))
    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Invites a user to an organization.")
  @PostMapping("/{organizationId}/invitations")
  fun inviteOrganizationUser(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @RequestBody payload: InviteOrganizationUserRequestPayload,
  ): SimpleSuccessResponsePayload {
    if (!emailValidator.isValid(payload.email)) {
      throw BadRequestException("Invalid email address")
    }

    organizationService.invite(
        payload.email, organizationId, payload.role, payload.projectIds ?: emptyList())
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess(description = "Invitation resent.")
  @ApiResponses(
      ApiResponse(
          responseCode = "409",
          description =
              "The user is already a member of the organization. This may mean they have already " +
                  "accepted the invitation.",
          content = [Content(schema = Schema(implementation = SimpleErrorResponsePayload::class))]),
      ApiResponse(
          responseCode = "429",
          description = "Too soon since the last invitation was sent.",
          content = [Content(schema = Schema(implementation = SimpleErrorResponsePayload::class))],
          headers =
              [
                  Header(
                      name = "Retry-After",
                      description =
                          "Number of seconds remaining before the invitation can be resent.",
                      schema = Schema(type = "integer"))]))
  @ApiResponse404(description = "The user does not exist or does not have a pending invitation.")
  @Operation(summary = "Resends an invitation message to a user with a pending invitation.")
  @PostMapping("/{organizationId}/invitations/{userId}/resend")
  fun resendOrganizationUserInvitation(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @PathVariable("userId") userId: UserId,
  ): SimpleSuccessResponsePayload {
    try {
      organizationService.resendInvitation(organizationId, userId)
      return SimpleSuccessResponsePayload()
    } catch (e: InvitationTooRecentException) {
      val retryAfterSeconds = Duration.between(clock.instant(), e.retryAfter).seconds
      throw TooManyRequestsException("Too soon since most recent invitation", retryAfterSeconds)
    }
  }

  @GetMapping("/{organizationId}/users")
  @Operation(summary = "Lists the users in an organization.")
  fun listOrganizationUsers(
      @PathVariable("organizationId") organizationId: OrganizationId,
  ): ListOrganizationUsersResponsePayload {
    val users = organizationStore.fetchUsers(organizationId)
    return ListOrganizationUsersResponsePayload(users.map { OrganizationUserPayload(it) })
  }

  @GetMapping("/{organizationId}/users/{userId}")
  @Operation(summary = "Gets information about a user's membership in an organization.")
  fun getOrganizationUser(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @PathVariable("userId") userId: UserId,
  ): GetOrganizationUserResponsePayload {
    val model =
        try {
          organizationStore.fetchUser(organizationId, userId)
        } catch (e: UserNotFoundException) {
          throw NotFoundException(
              "User $userId does not exist or is not a member of organization $organizationId")
        }

    return GetOrganizationUserResponsePayload(OrganizationUserPayload(model))
  }

  private fun getRole(model: OrganizationModel): Role {
    return currentUser().organizationRoles[model.id]
        ?: throw OrganizationNotFoundException(model.id)
  }
}

data class InviteOrganizationUserRequestPayload(
    val email: String,
    val role: Role,
    val projectIds: List<ProjectId>?,
)

data class UpdateOrganizationRequestPayload(
    @Schema(
        description = "ISO 3166 alpha-2 code of organization's country.",
        example = "AU",
        minLength = 2,
        maxLength = 2)
    val countryCode: String?,
    @Schema(
        description =
            "ISO 3166-2 code of organization's country subdivision (state, province, region, " +
                "etc.) This is the full ISO 3166-2 code including the country prefix. If this is " +
                "set, countryCode must also be set.",
        example = "US-HI",
        minLength = 4,
        maxLength = 6)
    val countrySubdivisionCode: String?,
    val description: String?,
    val name: String,
) {
  fun toRow(): OrganizationsRow {
    return OrganizationsRow(
        countryCode = countryCode,
        countrySubdivisionCode = countrySubdivisionCode,
        description = description,
        name = name,
    )
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrganizationPayload(
    @Schema(
        description = "ISO 3166 alpha-2 code of organization's country.",
        example = "AU",
        minLength = 2,
        maxLength = 2)
    val countryCode: String?,
    @Schema(
        description =
            "ISO 3166-2 code of organization's country subdivision (state, province, region, " +
                "etc.) This is the full ISO 3166-2 code including the country prefix. If this is " +
                "set, countryCode will also be set.",
        example = "US-HI",
        minLength = 4,
        maxLength = 6)
    val countrySubdivisionCode: String?,
    val description: String?,
    val id: OrganizationId,
    val name: String,
    @Schema(description = "This organization's projects. Omitted if depth is \"Organization\".")
    val projects: List<ProjectPayload>?,
    @Schema(
        description = "The current user's role in the organization.",
    )
    val role: Role,
) {
  constructor(
      model: OrganizationModel,
      role: Role,
  ) : this(
      model.countryCode,
      model.countrySubdivisionCode,
      model.description,
      model.id,
      model.name,
      model.projects?.map { ProjectPayload(it) },
      role,
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrganizationUserPayload(
    val email: String,
    val id: UserId,
    @Schema(
        description =
            "The user's first name. Not visible for users who have been invited but have not yet " +
                "accepted the invitation.")
    val firstName: String?,
    @Schema(
        description =
            "The user's last name. Not visible for users who have been invited but have not yet " +
                "accepted the invitation.")
    val lastName: String?,
    @Schema(
        description =
            "If the user has been invited to the organization but has not yet accepted the " +
                "invitation, the time when the most recent invitation was sent. Not present if " +
                "there is no pending invitation.")
    val pendingInvitationTime: Instant?,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "IDs of projects the user is in. Users with admin and owner roles always " +
                        "have access to all projects."))
    val projectIds: List<ProjectId>,
    val role: Role,
) {
  constructor(
      model: OrganizationUserModel
  ) : this(
      email = model.email,
      firstName = model.firstName,
      id = model.userId,
      lastName = model.lastName,
      pendingInvitationTime = model.pendingInvitationTime,
      projectIds = model.projectIds,
      role = model.role,
  )
}

data class GetOrganizationResponsePayload(val organization: OrganizationPayload) :
    SuccessResponsePayload

data class GetOrganizationUserResponsePayload(val user: OrganizationUserPayload) :
    SuccessResponsePayload

data class ListOrganizationUsersResponsePayload(val users: List<OrganizationUserPayload>) :
    SuccessResponsePayload

data class ListOrganizationsResponsePayload(val organizations: List<OrganizationPayload>) :
    SuccessResponsePayload
