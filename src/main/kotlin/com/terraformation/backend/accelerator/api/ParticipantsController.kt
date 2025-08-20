package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ParticipantService
import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.model.ExistingParticipantModel
import com.terraformation.backend.accelerator.model.ParticipantModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.DeleteMapping
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
    private val participantService: ParticipantService,
    private val participantStore: ParticipantStore,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val projectStore: ProjectStore,
) {
  @ApiResponse200
  @Operation(summary = "Creates a new participant.")
  @PostMapping
  fun createParticipant(
      @RequestBody payload: CreateParticipantRequestPayload
  ): GetParticipantResponsePayload {
    val model =
        participantService.create(
            ParticipantModel.create(
                cohortId = payload.cohortId,
                name = payload.name,
                projectIds = payload.projectIds?.toSet() ?: emptySet(),
            )
        )

    return GetParticipantResponsePayload(makeParticipantPayload(model))
  }

  @ApiResponse200
  @ApiResponse404
  @ApiResponse409("There are projects associated with the participant.")
  @DeleteMapping("/{participantId}")
  @Operation(summary = "Deletes a participant that has no projects.")
  fun deleteParticipant(@PathVariable participantId: ParticipantId): SimpleSuccessResponsePayload {
    participantStore.delete(participantId)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "List participants and their assigned projects.")
  fun listParticipants(): ListParticipantsResponsePayload {
    val models = participantStore.findAll()

    return ListParticipantsResponsePayload(models.map { makeParticipantPayload(it) })
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{participantId}")
  @Operation(summary = "Gets information about a participant and its assigned projects.")
  fun getParticipant(@PathVariable participantId: ParticipantId): GetParticipantResponsePayload {
    val model = participantStore.fetchOneById(participantId)

    return GetParticipantResponsePayload(makeParticipantPayload(model))
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(summary = "Updates a participant's information.")
  @PutMapping("/{participantId}")
  fun updateParticipant(
      @PathVariable participantId: ParticipantId,
      @RequestBody payload: UpdateParticipantRequestPayload,
  ): SimpleSuccessResponsePayload {
    participantService.update(participantId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  private fun makeParticipantPayload(model: ExistingParticipantModel): ParticipantPayload {
    // The number of projects per participant should never exceed low single digits and will usually
    // be 0 or 1, so fetching them one at a time shouldn't be a bottleneck. If we ever have hundreds
    // of projects per participant, we can add a custom query to fetch them all at once.
    val cohort = model.cohortId?.let { cohortStore.fetchOneById(it) }
    val projects = model.projectIds.map { projectStore.fetchOneById(it) }
    val organizationsById =
        projects
            .map { it.organizationId }
            .distinct()
            .map { organizationStore.fetchOneById(it) }
            .associateBy { it.id }

    val projectDetails =
        projectAcceleratorDetailsService.fetchForParticipant(model.id).associateBy { detail ->
          detail.projectId
        }
    val projectPayloads =
        projects.map { project ->
          val organization = organizationsById[project.organizationId]!!
          ParticipantProjectPayload(
              organization.id,
              organization.name,
              projectDetails[project.id]?.dealName,
              project.id,
              project.name,
          )
        }

    return ParticipantPayload(
        cohortId = model.cohortId,
        cohortName = cohort?.name,
        cohortPhase = cohort?.phase,
        id = model.id,
        name = model.name,
        projects = projectPayloads,
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
    val id: ParticipantId,
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

data class ListParticipantsResponsePayload(
    val participants: List<ParticipantPayload>,
) : SuccessResponsePayload

data class GetParticipantResponsePayload(
    val participant: ParticipantPayload,
) : SuccessResponsePayload

data class UpdateParticipantRequestPayload(
    @Schema(
        description =
            "Assign the participant to this cohort. If null, remove the participant from its " +
                "current cohort, if any."
    )
    val cohortId: CohortId?,
    val name: String,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "Set the participant's list of assigned projects to this. If projects are " +
                        "currently assigned to the participant but aren't included in this list, " +
                        "they will be removed from the participant."
            )
    )
    val projectIds: Set<ProjectId>,
) {
  fun applyTo(model: ExistingParticipantModel) =
      model.copy(
          cohortId = cohortId,
          name = name,
          projectIds = projectIds,
      )
}
