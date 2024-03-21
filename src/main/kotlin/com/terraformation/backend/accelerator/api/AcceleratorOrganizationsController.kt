package com.terraformation.backend.accelerator.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.accelerator.db.AcceleratorOrganizationStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/organizations")
@RestController
class AcceleratorOrganizationsController(
    private val acceleratorOrganizationStore: AcceleratorOrganizationStore,
) {
  @GetMapping
  @Operation(
      summary = "Lists organizations with the Accelerator internal tag and their projects.",
      description =
          "By default, only lists tagged organizations that have projects that have not been " +
              "assigned to participants yet.")
  fun listAcceleratorOrganizations(
      @Parameter(
          description = "Whether to also include projects that have been assigned to participants.")
      @RequestParam
      includeParticipants: Boolean?,
  ): ListAcceleratorOrganizationsResponsePayload {
    val organizations =
        if (includeParticipants == true) {
          acceleratorOrganizationStore.findAll()
        } else {
          acceleratorOrganizationStore.fetchWithUnassignedProjects()
        }

    return ListAcceleratorOrganizationsResponsePayload(
        organizations.map { (organization, projects) ->
          AcceleratorOrganizationPayload(organization, projects)
        })
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AcceleratorProjectPayload(
    val id: ProjectId,
    val name: String,
    val participantId: ParticipantId?,
) {
  constructor(model: ExistingProjectModel) : this(model.id, model.name, model.participantId)
}

data class AcceleratorOrganizationPayload(
    val id: OrganizationId,
    val name: String,
    val projects: List<AcceleratorProjectPayload>,
) {
  constructor(
      model: OrganizationModel,
      projects: List<ExistingProjectModel>
  ) : this(model.id, model.name, projects.map { AcceleratorProjectPayload(it) })
}

data class ListAcceleratorOrganizationsResponsePayload(
    val organizations: List<AcceleratorOrganizationPayload>,
) : SuccessResponsePayload
