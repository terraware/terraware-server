package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.accelerator.api.TerraformationContactUserPayload
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.OrganizationService
import com.terraformation.backend.customer.db.OrganizationFeatureStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationFeature
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.OrganizationUserModel
import com.terraformation.backend.db.CannotRemoveLastOwnerException
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.default_schema.ManagedLocationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.OrganizationType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationsRow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.apache.commons.validator.routines.EmailValidator
import org.springframework.web.bind.annotation.DeleteMapping
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
    private val organizationService: OrganizationService,
    private val organizationStore: OrganizationStore,
    private val organizationFeatureStore: OrganizationFeatureStore,
) {
  private val emailValidator = EmailValidator.getInstance()

  @GetMapping
  @Operation(
      summary = "Lists all organizations.",
      description = "Lists all organizations the user can access through organization roles.",
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
    val model = organizationStore.fetchOneById(organizationId, depth)
    return GetOrganizationResponsePayload(OrganizationPayload(model, getRole(model)))
  }

  @Operation(summary = "Creates a new organization.")
  @PostMapping
  fun createOrganization(
      @RequestBody @Valid payload: CreateOrganizationRequestPayload
  ): GetOrganizationResponsePayload {
    val model =
        organizationStore.createWithAdmin(
            payload.toRow(),
            payload.managedLocationTypes ?: emptySet(),
        )
    return GetOrganizationResponsePayload(OrganizationPayload(model, Role.Owner))
  }

  @Operation(summary = "Updates an existing organization.")
  @PutMapping("/{organizationId}")
  fun updateOrganization(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @RequestBody @Valid payload: UpdateOrganizationRequestPayload,
  ): SimpleSuccessResponsePayload {
    organizationStore.update(payload.toRow().copy(id = organizationId))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse409(description = "The organization has other members and cannot be deleted.")
  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Deletes an existing organization.",
      description =
          "Organizations can only be deleted if they have no members other than the current user.",
  )
  @DeleteMapping("/{organizationId}")
  fun deleteOrganization(
      @PathVariable("organizationId") organizationId: OrganizationId
  ): SimpleSuccessResponsePayload {
    try {
      organizationService.deleteOrganization(organizationId)
      return SimpleSuccessResponsePayload()
    } catch (e: OrganizationHasOtherUsersException) {
      throw WebApplicationException(e.message, Response.Status.CONFLICT)
    }
  }

  @Operation(summary = "Lists the features available to an organization.")
  @GetMapping("/{organizationId}/features")
  fun listOrganizationFeatures(
      @PathVariable("organizationId") organizationId: OrganizationId
  ): ListOrganizationFeaturesResponsePayload {
    val features = organizationFeatureStore.listOrganizationFeatureProjects(organizationId)
    return ListOrganizationFeaturesResponsePayload(features)
  }

  @Operation(summary = "Lists the roles in an organization.")
  @GetMapping("/{organizationId}/roles")
  fun listOrganizationRoles(
      @PathVariable("organizationId") organizationId: OrganizationId
  ): ListOrganizationRolesResponsePayload {
    val roleCounts = organizationStore.countRoleUsers(organizationId)
    return ListOrganizationRolesResponsePayload(
        roleCounts.map { (role, count) -> OrganizationRolePayload(role, count) }
    )
  }

  @GetMapping("/{organizationId}/users")
  @Operation(summary = "Lists the users in an organization.")
  fun listOrganizationUsers(
      @PathVariable("organizationId") organizationId: OrganizationId,
  ): ListOrganizationUsersResponsePayload {
    val users = organizationStore.fetchUsers(organizationId)
    return ListOrganizationUsersResponsePayload(users.map { OrganizationUserPayload(it) })
  }

  @PostMapping("/{organizationId}/users")
  @Operation(summary = "Adds a user to an organization.")
  fun addOrganizationUser(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @RequestBody payload: AddOrganizationUserRequestPayload,
  ): CreateOrganizationUserResponsePayload {
    if (!emailValidator.isValid(payload.email)) {
      throw BadRequestException("Field value has incorrect format: email")
    }

    val userId = organizationService.addUser(payload.email, organizationId, payload.role)
    return CreateOrganizationUserResponsePayload(userId)
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
              "User $userId does not exist or is not a member of organization $organizationId"
          )
        }

    return GetOrganizationUserResponsePayload(OrganizationUserPayload(model))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404("The user is not a member of the organization.")
  @ApiResponse409(
      "The user is the organization's only owner and an organization must have at least one owner."
  )
  @DeleteMapping("/{organizationId}/users/{userId}")
  @Operation(
      summary = "Removes a user from an organization.",
      description = "Does not remove any data created by the user.",
  )
  fun deleteOrganizationUser(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @PathVariable("userId") userId: UserId,
  ): SimpleSuccessResponsePayload {
    try {
      organizationStore.removeUser(organizationId, userId)
    } catch (e: CannotRemoveLastOwnerException) {
      throw WebApplicationException(e.message, Response.Status.CONFLICT)
    }

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404("The user is not a member of the organization.")
  @ApiResponse409(
      "An organization must have at least one owner; cannot change the role of an " +
          "organization's only owner."
  )
  @PutMapping("/{organizationId}/users/{userId}")
  @Operation(
      summary = "Updates the user's organization information.",
      description =
          "Only includes organization-level information that can be modified by organization " +
              "administrators.",
  )
  fun updateOrganizationUser(
      @PathVariable("organizationId") organizationId: OrganizationId,
      @PathVariable("userId") userId: UserId,
      @RequestBody payload: UpdateOrganizationUserRequestPayload,
  ): SimpleSuccessResponsePayload {
    // Currently, the only setting is the user's role.
    try {
      organizationStore.setUserRole(organizationId, userId, payload.role)
    } catch (e: UserNotFoundException) {
      throw NotFoundException(
          "User $userId does not exist or is not a member of organization $organizationId"
      )
    } catch (e: CannotRemoveLastOwnerException) {
      throw WebApplicationException(e.message, Response.Status.CONFLICT)
    }
    return SimpleSuccessResponsePayload()
  }

  private fun getRole(model: OrganizationModel): Role? {
    return currentUser().organizationRoles[model.id]
  }
}

data class AddOrganizationUserRequestPayload(
    val email: String,
    val role: Role,
)

data class UpdateOrganizationUserRequestPayload(
    val role: Role,
)

data class CreateOrganizationRequestPayload(
    @Schema(description = "ISO 3166 alpha-2 code of organization's country.", example = "AU")
    @field:Size(min = 2, max = 2)
    val countryCode: String?,
    @Schema(
        description =
            "ISO 3166-2 code of organization's country subdivision (state, province, region, " +
                "etc.) This is the full ISO 3166-2 code including the country prefix. If this is " +
                "set, countryCode must also be set.",
        example = "US-HI",
    )
    @field:Size(min = 4, max = 6)
    val countrySubdivisionCode: String?,
    val description: String?,
    val managedLocationTypes: Set<ManagedLocationType>?,
    @field:NotEmpty val name: String,
    val organizationType: OrganizationType?,
    @Schema(description = "Non-empty additional description of organization when type is Other.")
    @field:Size(max = 100)
    val organizationTypeDetails: String?,
    val timeZone: ZoneId?,
    @Schema(
        description = "Website of organization, no restrictions on format.",
    )
    val website: String?,
) {
  fun toRow(): OrganizationsRow {
    return OrganizationsRow(
        countryCode = countryCode,
        countrySubdivisionCode = countrySubdivisionCode,
        description = description,
        name = name,
        organizationTypeId = organizationType,
        organizationTypeDetails = organizationTypeDetails,
        timeZone = timeZone,
        website = website,
    )
  }
}

data class UpdateOrganizationRequestPayload(
    @Schema(description = "ISO 3166 alpha-2 code of organization's country.", example = "AU")
    @field:Size(min = 2, max = 2)
    val countryCode: String?,
    @Schema(
        description =
            "ISO 3166-2 code of organization's country subdivision (state, province, region, " +
                "etc.) This is the full ISO 3166-2 code including the country prefix. If this is " +
                "set, countryCode must also be set.",
        example = "US-HI",
    )
    @field:Size(min = 4, max = 6)
    val countrySubdivisionCode: String?,
    val description: String?,
    @field:NotEmpty val name: String,
    val organizationType: OrganizationType?,
    @Schema(description = "Non-empty additional description of organization when type is Other.")
    @field:Size(max = 100)
    val organizationTypeDetails: String?,
    val timeZone: ZoneId?,
    val website: String?,
) {
  fun toRow(): OrganizationsRow {
    return OrganizationsRow(
        countryCode = countryCode,
        countrySubdivisionCode = countrySubdivisionCode,
        description = description,
        name = name,
        organizationTypeId = organizationType,
        organizationTypeDetails = organizationTypeDetails,
        timeZone = timeZone,
        website = website,
    )
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrganizationPayload(
    @Schema(description = "Whether this organization can submit reports to Terraformation.")
    val canSubmitReports: Boolean,
    @Schema(description = "ISO 3166 alpha-2 code of organization's country.", example = "AU")
    @field:Size(min = 2, max = 2)
    val countryCode: String?,
    @Schema(
        description =
            "ISO 3166-2 code of organization's country subdivision (state, province, region, " +
                "etc.) This is the full ISO 3166-2 code including the country prefix. If this is " +
                "set, countryCode will also be set.",
        example = "US-HI",
    )
    @field:Size(min = 4, max = 6)
    val countrySubdivisionCode: String?,
    val createdTime: Instant,
    val description: String?,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "This organization's facilities. Only included if depth is \"Facility\"."
            )
    )
    val facilities: List<FacilityPayload>?,
    val id: OrganizationId,
    val name: String,
    val organizationType: OrganizationType?,
    val organizationTypeDetails: String?,
    @Schema(
        description =
            "The current user's role in the organization. Absent if the current user is not a " +
                "member of the organization but is able to read it thanks to a global role."
    )
    val role: Role?,
    val tfContactUser: TerraformationContactUserPayload?,
    val timeZone: ZoneId?,
    @Schema(
        description = "The total number of users in the organization, including the current user."
    )
    val totalUsers: Int,
    val website: String?,
) {
  constructor(
      model: OrganizationModel,
      role: Role?,
      tfContactUser: IndividualUser? = null,
  ) : this(
      canSubmitReports = InternalTagIds.Reporter in model.internalTags,
      model.countryCode,
      model.countrySubdivisionCode,
      model.createdTime.truncatedTo(ChronoUnit.SECONDS),
      model.description,
      model.facilities?.map { FacilityPayload(it) },
      model.id,
      model.name,
      model.organizationType,
      model.organizationTypeDetails,
      role,
      tfContactUser = tfContactUser?.let { TerraformationContactUserPayload(it) },
      model.timeZone,
      model.totalUsers,
      model.website,
  )
}

data class OrganizationFeaturePayload(
    val enabled: Boolean,
    val projectIds: List<ProjectId>,
) {
  constructor(
      projectIds: Set<ProjectId>
  ) : this(
      enabled = projectIds.isNotEmpty(),
      projectIds = projectIds.toList(),
  )
}

data class OrganizationRolePayload(
    val role: Role,
    @Schema(description = "Total number of users in the organization with this role.")
    val totalUsers: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrganizationUserPayload(
    @Schema(description = "Date and time the user was added to the organization.")
    val addedTime: Instant,
    val email: String,
    val id: UserId,
    @Schema(
        description =
            "The user's first name. Not present if the user has been added to the organization " +
                "but has not signed up for an account yet."
    )
    val firstName: String?,
    @Schema(
        description =
            "The user's last name. Not present if the user has been added to the organization " +
                "but has not signed up for an account yet."
    )
    val lastName: String?,
    val role: Role,
) {
  constructor(
      model: OrganizationUserModel
  ) : this(
      addedTime = model.createdTime.truncatedTo(ChronoUnit.SECONDS),
      email = model.email,
      firstName = model.firstName,
      id = model.userId,
      lastName = model.lastName,
      role = model.role,
  )
}

data class GetOrganizationResponsePayload(val organization: OrganizationPayload) :
    SuccessResponsePayload

data class GetOrganizationUserResponsePayload(val user: OrganizationUserPayload) :
    SuccessResponsePayload

data class ListOrganizationFeaturesResponsePayload(
    val applications: OrganizationFeaturePayload,
    val deliverables: OrganizationFeaturePayload,
    val modules: OrganizationFeaturePayload,
    val reports: OrganizationFeaturePayload,
    val seedFundReports: OrganizationFeaturePayload,
) : SuccessResponsePayload {
  constructor(
      features: Map<OrganizationFeature, Set<ProjectId>>
  ) : this(
      applications =
          OrganizationFeaturePayload(features[OrganizationFeature.Applications] ?: emptySet()),
      deliverables =
          OrganizationFeaturePayload(features[OrganizationFeature.Deliverables] ?: emptySet()),
      modules = OrganizationFeaturePayload(features[OrganizationFeature.Modules] ?: emptySet()),
      reports = OrganizationFeaturePayload(features[OrganizationFeature.Reports] ?: emptySet()),
      seedFundReports =
          OrganizationFeaturePayload(features[OrganizationFeature.SeedFundReports] ?: emptySet()),
  )
}

data class ListOrganizationRolesResponsePayload(val roles: List<OrganizationRolePayload>) :
    SuccessResponsePayload

data class ListOrganizationUsersResponsePayload(val users: List<OrganizationUserPayload>) :
    SuccessResponsePayload

data class CreateOrganizationUserResponsePayload(
    @Schema(
        description = "The ID of the newly-added user.",
    )
    val id: UserId
) : SuccessResponsePayload

data class ListOrganizationsResponsePayload(val organizations: List<OrganizationPayload>) :
    SuccessResponsePayload
