package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.log.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/participants")
@RestController
class ParticipantsController(
    private val cohortStore: CohortStore,
    private val organizationStore: OrganizationStore,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val projectStore: ProjectStore,
) {
  private val log = perClassLogger()

  @ApiResponse200
  @Operation(
      summary = "OBSOLETE",
      description =
          "This used to create a new participant; now it just sets the cohort on a project.",
      deprecated = true,
  )
  @PostMapping
  fun createParticipant(
      @RequestBody payload: CreateParticipantRequestPayload
  ): GetParticipantResponsePayload {
    if (payload.projectIds == null || payload.projectIds.isEmpty()) {
      log.error("Ignoring attempt to create a participant without any projects")
      return GetParticipantResponsePayload(
          ParticipantPayload(payload.cohortId, null, null, ProjectId(0), payload.name, emptyList())
      )
    }
    if (payload.projectIds.size > 1) {
      throw IllegalArgumentException("Multi-project participants not supported")
    }

    val projectId = payload.projectIds.first()
    projectStore.updateCohort(projectId, payload.cohortId)
    val model = projectStore.fetchOneById(projectId)

    return GetParticipantResponsePayload(makeParticipantPayload(model))
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{participantId}")
  @Operation(
      summary = "OBSOLETE",
      description = "Gets a project's cohort.",
      deprecated = true,
  )
  fun getParticipant(@PathVariable participantId: ProjectId): GetParticipantResponsePayload {
    val model = projectStore.fetchOneById(participantId)

    return GetParticipantResponsePayload(makeParticipantPayload(model))
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(
      summary = "OBSOLETE",
      description = "Updates a project's cohort.",
      deprecated = true,
  )
  @PutMapping("/{participantId}")
  fun updateParticipant(
      @PathVariable participantId: ProjectId,
      @RequestBody payload: UpdateParticipantRequestPayload,
  ): SimpleSuccessResponsePayload {
    projectStore.updateCohort(participantId, payload.cohortId)

    return SimpleSuccessResponsePayload()
  }

  private fun makeParticipantPayload(model: ExistingProjectModel): ParticipantPayload {
    val cohort = model.cohortId?.let { cohortStore.fetchOneById(it) }
    val organization = organizationStore.fetchOneById(model.organizationId)

    val projectDetails = projectAcceleratorDetailsService.fetchOneById(model.id)
    val projectPayload =
        ParticipantProjectPayload(
            organization.id,
            organization.name,
            projectDetails.dealName,
            model.id,
            model.name,
        )

    return ParticipantPayload(
        cohortId = model.cohortId,
        cohortName = cohort?.name,
        cohortPhase = cohort?.phase,
        id = model.id,
        name = model.name,
        projects = listOf(projectPayload),
    )
  }
}

data class ParticipantProjectPayload(
    val organizationId: OrganizationId,
    val organizationName: String,
    val projectDealName: String?,
    val projectId: ProjectId,
    val projectName: String,
)

data class ParticipantPayload(
    val cohortId: CohortId?,
    val cohortName: String?,
    val cohortPhase: CohortPhase?,
    val id: ProjectId,
    val name: String,
    val projects: List<ParticipantProjectPayload>,
)

data class CreateParticipantRequestPayload(
    @Schema(
        description =
            "Assign the participant to this cohort. If null, the participant will not be " +
                "assigned to any cohort initially."
    )
    val cohortId: CohortId?,
    val name: String,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "Assign these projects to the new participant. If projects are already " +
                        "assigned to other participants, they will be reassigned to the new one."
            )
    )
    val projectIds: List<ProjectId>?,
)

data class GetParticipantResponsePayload(
    val participant: ParticipantPayload,
) : SuccessResponsePayload

data class UpdateParticipantRequestPayload(
    @Schema(
        description =
            "Assign the project to this cohort. If null, remove the project from its " +
                "current cohort, if any."
    )
    val cohortId: CohortId?,
)
