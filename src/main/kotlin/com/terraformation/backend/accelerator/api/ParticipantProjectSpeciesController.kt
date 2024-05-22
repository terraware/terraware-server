package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ParticipantProjectSpeciesService
import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectsForSpecies
import com.terraformation.backend.accelerator.model.SpeciesForParticipantProject
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator")
@RestController
class ParticipantProjectSpeciesController(
    private val participantProjectSpeciesService: ParticipantProjectSpeciesService,
    private val participantProjectSpeciesStore: ParticipantProjectSpeciesStore,
) {
  @ApiResponse200
  @PostMapping("/projects/species/assign")
  @Operation(
      summary =
          "Creates a new participant project species entry for every project ID and species ID pairing.")
  fun assignParticipantProjectSpecies(
      @RequestBody payload: AssignParticipantProjectSpeciesPayload
  ): SimpleSuccessResponsePayload {
    participantProjectSpeciesService.create(payload.projectIds, payload.speciesIds)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @PostMapping("/projects/species")
  @Operation(summary = "Creates a new participant project species entry.")
  fun createParticipantProjectSpecies(
      @RequestBody payload: CreateParticipantProjectSpeciesPayload
  ): GetParticipantProjectSpeciesResponsePayload {
    val model =
        participantProjectSpeciesService.create(
            NewParticipantProjectSpeciesModel(
                id = null,
                projectId = payload.projectId,
                rationale = payload.rationale,
                speciesId = payload.speciesId,
            ))

    return makeGetResponse(model)
  }

  @ApiResponse200
  @ApiResponse404
  @DeleteMapping("/projects/species")
  @Operation(summary = "Deletes participant project species entries.")
  fun deleteParticipantProjectSpecies(
      @RequestBody payload: DeleteParticipantProjectSpeciesPayload
  ): SimpleSuccessResponsePayload {
    participantProjectSpeciesStore.delete(payload.participantProjectSpeciesIds)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/projects/species/{participantProjectSpeciesId}")
  @Operation(summary = "Gets information about a participant project species.")
  fun getParticipantProjectSpecies(
      @PathVariable participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): GetParticipantProjectSpeciesResponsePayload {
    val model = participantProjectSpeciesStore.fetchOneById(participantProjectSpeciesId)

    return makeGetResponse(model)
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/species/{speciesId}/projects")
  @Operation(
      summary =
          "Gets all participant projects associated to a species with active deliverable information if applicable.")
  fun getProjectsForSpecies(
      @PathVariable speciesId: SpeciesId
  ): GetParticipantProjectsForSpeciesResponsePayload {
    val results = participantProjectSpeciesStore.fetchParticipantProjectsForSpecies(speciesId)

    return GetParticipantProjectsForSpeciesResponsePayload(
        results.map { ParticipantProjectForSpeciesPayload(it) })
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/projects/{projectId}/species")
  @Operation(summary = "Gets all species associated to a participant project.")
  fun getSpeciesForProject(
      @PathVariable projectId: ProjectId
  ): GetSpeciesForParticipantProjectsResponsePayload {
    val results = participantProjectSpeciesStore.fetchSpeciesForParticipantProjects(projectId)

    return GetSpeciesForParticipantProjectsResponsePayload(
        results.map { SpeciesForParticipantProjectPayload(it) })
  }

  @ApiResponse200
  @ApiResponse404
  @PutMapping("/projects/species/{participantProjectSpeciesId}")
  @Operation(summary = "Updates a participant project species entry.")
  fun updateParticipantProjectSpecies(
      @PathVariable participantProjectSpeciesId: ParticipantProjectSpeciesId,
      @RequestBody payload: UpdateParticipantProjectSpeciesPayload
  ): SimpleSuccessResponsePayload {
    participantProjectSpeciesStore.update(participantProjectSpeciesId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  private fun makeGetResponse(
      model: ExistingParticipantProjectSpeciesModel
  ): GetParticipantProjectSpeciesResponsePayload =
      GetParticipantProjectSpeciesResponsePayload(
          ParticipantProjectSpeciesPayload(
              feedback = model.feedback,
              id = model.id,
              internalComment = model.internalComment,
              projectId = model.projectId,
              rationale = model.rationale,
              speciesId = model.speciesId,
              speciesNativeCategory = model.speciesNativeCategory,
              submissionStatus = model.submissionStatus,
          ))
}

data class AssignParticipantProjectSpeciesPayload(
    val projectIds: Set<ProjectId>,
    val speciesIds: Set<SpeciesId>
)

data class CreateParticipantProjectSpeciesPayload(
    val projectId: ProjectId,
    val rationale: String?,
    val speciesId: SpeciesId,
)

data class DeleteParticipantProjectSpeciesPayload(
    val participantProjectSpeciesIds: Set<ParticipantProjectSpeciesId>,
)

data class ParticipantProjectSpeciesPayload(
    val feedback: String?,
    val id: ParticipantProjectSpeciesId,
    val internalComment: String?,
    val projectId: ProjectId,
    val rationale: String?,
    val speciesId: SpeciesId,
    val speciesNativeCategory: SpeciesNativeCategory?,
    val submissionStatus: SubmissionStatus,
)

data class GetParticipantProjectSpeciesResponsePayload(
    val participantProjectSpecies: ParticipantProjectSpeciesPayload,
) : SuccessResponsePayload

data class ParticipantProjectForSpeciesPayload(
    val activeDeliverableId: DeliverableId?,
    val participantProjectSpeciesId: ParticipantProjectSpeciesId,
    val participantProjectSpeciesSubmissionStatus: SubmissionStatus,
    val projectId: ProjectId,
    val projectName: String,
    val speciesId: SpeciesId,
) {
  constructor(
      model: ParticipantProjectsForSpecies
  ) : this(
      activeDeliverableId = model.activeDeliverableId,
      participantProjectSpeciesId = model.participantProjectSpeciesId,
      participantProjectSpeciesSubmissionStatus = model.participantProjectSpeciesSubmissionStatus,
      projectId = model.projectId,
      projectName = model.projectName,
      speciesId = model.speciesId)
}

data class GetParticipantProjectsForSpeciesResponsePayload(
    val participantProjectsForSpecies: List<ParticipantProjectForSpeciesPayload>,
) : SuccessResponsePayload

data class SpeciesForParticipantProjectPayload(
    val participantProjectSpeciesId: ParticipantProjectSpeciesId,
    val participantProjectSpeciesRationale: String?,
    val participantProjectSpeciesSubmissionStatus: SubmissionStatus,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
    val speciesCommonName: String?,
    val speciesScientificName: String,
) {
  constructor(
      model: SpeciesForParticipantProject
  ) : this(
      participantProjectSpeciesId = model.participantProjectSpeciesId,
      participantProjectSpeciesRationale = model.participantProjectSpeciesRationale,
      participantProjectSpeciesSubmissionStatus = model.participantProjectSpeciesSubmissionStatus,
      projectId = model.projectId,
      speciesId = model.speciesId,
      speciesCommonName = model.speciesCommonName,
      speciesScientificName = model.speciesScientificName,
  )
}

data class GetSpeciesForParticipantProjectsResponsePayload(
    val speciesForParticipantProjects: List<SpeciesForParticipantProjectPayload>,
) : SuccessResponsePayload

data class UpdateParticipantProjectSpeciesPayload(
    val feedback: String?,
    val internalComment: String?,
    val rationale: String?,
    val speciesNativeCategory: SpeciesNativeCategory?,
    val submissionStatus: SubmissionStatus,
) {
  fun applyTo(model: ExistingParticipantProjectSpeciesModel) =
      model.copy(
          feedback = feedback,
          internalComment = internalComment,
          rationale = rationale,
          speciesNativeCategory = speciesNativeCategory,
          submissionStatus = submissionStatus)
}
