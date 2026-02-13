package com.terraformation.backend.accelerator.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.accelerator.db.AcceleratorOrganizationStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.OrganizationService
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/organizations")
@RestController
class AcceleratorOrganizationsController(
    private val acceleratorOrganizationStore: AcceleratorOrganizationStore,
    private val organizationService: OrganizationService,
    private val userStore: UserStore,
) {
  @GetMapping
  @Operation(
      summary = "Lists accelerator related organizations and their projects.",
  )
  fun listAcceleratorOrganizations(): ListAcceleratorOrganizationsResponsePayload {
    val organizations = acceleratorOrganizationStore.findAll()

    return ListAcceleratorOrganizationsResponsePayload(
        organizations.map { (organization, projects) ->
          val contacts = userStore.getTerraformationContactUsers(organization.id)
          AcceleratorOrganizationPayload(organization, projects, contacts)
        }
    )
  }

  @Operation(
      summary = "Assign a user as a Terraformation contact for an organization.",
      description = "The user will be added to the organization if they are not already a member.",
  )
  @PutMapping("/{organizationId}/tfContact")
  fun assignTerraformationContact(
      @PathVariable organizationId: OrganizationId,
      @RequestBody payload: AssignTerraformationContactRequestPayload,
  ): SimpleSuccessResponsePayload {
    organizationService.assignTerraformationContact(payload.userId, organizationId)

    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AcceleratorProjectPayload(
    val cohortId: CohortId?,
    val id: ProjectId,
    val name: String,
) {
  constructor(model: ExistingProjectModel) : this(model.cohortId, model.id, model.name)
}

data class TerraformationContactUserPayload(
    val userId: UserId,
    val email: String,
    val firstName: String?,
    val lastName: String?,
) {
  constructor(
      model: IndividualUser
  ) : this(model.userId, model.email, model.firstName, model.lastName)
}

data class AcceleratorOrganizationPayload(
    val id: OrganizationId,
    val name: String,
    val projects: List<AcceleratorProjectPayload>,
    val tfContactUser: TerraformationContactUserPayload?, // for backwards compatibility
    val tfContactUsers: List<TerraformationContactUserPayload> = emptyList(),
) {
  constructor(
      model: OrganizationModel,
      projects: List<ExistingProjectModel>,
      tfContactUsers: List<IndividualUser> = emptyList(),
  ) : this(
      model.id,
      model.name,
      projects.map { AcceleratorProjectPayload(it) },
      tfContactUsers.firstOrNull()?.let { TerraformationContactUserPayload(it) },
      tfContactUsers.map { TerraformationContactUserPayload(it) },
  )
}

data class AssignTerraformationContactRequestPayload(val userId: UserId)

data class ListAcceleratorOrganizationsResponsePayload(
    val organizations: List<AcceleratorOrganizationPayload>,
) : SuccessResponsePayload
