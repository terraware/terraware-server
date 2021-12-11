package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationsController(private val organizationStore: OrganizationStore) {
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

  private fun getRole(model: OrganizationModel): Role {
    return currentUser().organizationRoles[model.id]
        ?: throw OrganizationNotFoundException(model.id)
  }
}

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

data class GetOrganizationResponsePayload(val organization: OrganizationPayload) :
    SuccessResponsePayload

data class ListOrganizationsResponsePayload(val organizations: List<OrganizationPayload>) :
    SuccessResponsePayload
