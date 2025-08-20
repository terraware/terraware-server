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
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.customer.api.ProjectPayload
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import com.terraformation.backend.species.api.SpeciesResponseElement
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Produces
import java.nio.file.NoSuchFileException
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
          "Creates a new participant project species entry for every project ID and species ID pairing."
  )
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
                speciesNativeCategory = payload.speciesNativeCategory,
            )
        )

    return GetParticipantProjectSpeciesResponsePayload(ParticipantProjectSpeciesPayload(model))
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

    return GetParticipantProjectSpeciesResponsePayload(ParticipantProjectSpeciesPayload(model))
  }

  @ApiResponse(
      responseCode = "200",
      description = "The file was successfully retrieved.",
      content =
          [
              Content(
                  schema = Schema(type = "string", format = "binary"),
                  mediaType = MediaType.ALL_VALUE,
              )
          ],
  )
  @GetMapping("/projects/{projectId}/species/snapshots/{deliverableId}")
  @Operation(summary = "Creates a new participant project species entry.")
  @Produces
  fun getParticipantProjectSpeciesSnapshot(
      @PathVariable projectId: ProjectId,
      @PathVariable deliverableId: DeliverableId,
  ): ResponseEntity<InputStreamResource> {
    return try {
      participantProjectSpeciesService
          .readSubmissionSnapshotFile(projectId, deliverableId)
          .toResponseEntity { contentDisposition = ContentDisposition.attachment().build() }
    } catch (e: NoSuchFileException) {
      throw NotFoundException()
    }
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/species/{speciesId}/projects")
  @Operation(
      summary =
          "Gets all participant projects associated to a species with active deliverable information if applicable."
  )
  fun getProjectsForSpecies(
      @PathVariable speciesId: SpeciesId
  ): GetParticipantProjectsForSpeciesResponsePayload {
    val results = participantProjectSpeciesStore.fetchParticipantProjectsForSpecies(speciesId)

    return GetParticipantProjectsForSpeciesResponsePayload(
        results.map { ParticipantProjectForSpeciesPayload(it) }
    )
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/projects/{projectId}/species")
  @Operation(summary = "Gets all species associated to a participant project.")
  fun getSpeciesForProject(
      @PathVariable projectId: ProjectId
  ): GetSpeciesForParticipantProjectsResponsePayload {
    val results = participantProjectSpeciesStore.fetchSpeciesForParticipantProject(projectId)

    return GetSpeciesForParticipantProjectsResponsePayload(
        results.map { SpeciesForParticipantProjectPayload(it) }
    )
  }

  @ApiResponse200
  @ApiResponse404
  @PutMapping("/projects/species/{participantProjectSpeciesId}")
  @Operation(summary = "Updates a participant project species entry.")
  fun updateParticipantProjectSpecies(
      @PathVariable participantProjectSpeciesId: ParticipantProjectSpeciesId,
      @RequestBody payload: UpdateParticipantProjectSpeciesPayload,
  ): SimpleSuccessResponsePayload {
    participantProjectSpeciesStore.update(participantProjectSpeciesId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class AssignParticipantProjectSpeciesPayload(
    val projectIds: Set<ProjectId>,
    val speciesIds: Set<SpeciesId>,
)

data class CreateParticipantProjectSpeciesPayload(
    val projectId: ProjectId,
    val rationale: String?,
    val speciesId: SpeciesId,
    val speciesNativeCategory: SpeciesNativeCategory?,
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
) {
  constructor(
      model: ExistingParticipantProjectSpeciesModel
  ) : this(
      feedback = model.feedback,
      id = model.id,
      internalComment = model.internalComment,
      projectId = model.projectId,
      rationale = model.rationale,
      speciesId = model.speciesId,
      speciesNativeCategory = model.speciesNativeCategory,
      submissionStatus = model.submissionStatus,
  )
}

data class GetParticipantProjectSpeciesResponsePayload(
    val participantProjectSpecies: ParticipantProjectSpeciesPayload,
) : SuccessResponsePayload

data class ParticipantProjectForSpeciesPayload(
    @Schema(
        description =
            "This deliverable ID is associated to the active or most recent cohort module, if available."
    )
    val deliverableId: DeliverableId?,
    val participantProjectSpeciesId: ParticipantProjectSpeciesId,
    val participantProjectSpeciesSubmissionStatus: SubmissionStatus,
    val participantProjectSpeciesNativeCategory: SpeciesNativeCategory?,
    val projectId: ProjectId,
    val projectName: String,
    val speciesId: SpeciesId,
) {
  constructor(
      model: ParticipantProjectsForSpecies
  ) : this(
      deliverableId = model.deliverableId,
      participantProjectSpeciesId = model.participantProjectSpeciesId,
      participantProjectSpeciesSubmissionStatus = model.participantProjectSpeciesSubmissionStatus,
      participantProjectSpeciesNativeCategory = model.participantProjectSpeciesNativeCategory,
      projectId = model.projectId,
      projectName = model.projectName,
      speciesId = model.speciesId,
  )
}

data class GetParticipantProjectsForSpeciesResponsePayload(
    val participantProjectsForSpecies: List<ParticipantProjectForSpeciesPayload>,
) : SuccessResponsePayload

data class SpeciesForParticipantProjectPayload(
    val participantProjectSpecies: ParticipantProjectSpeciesPayload,
    val species: SpeciesResponseElement,
    val project: ProjectPayload,
) {
  constructor(
      model: SpeciesForParticipantProject
  ) : this(
      participantProjectSpecies = ParticipantProjectSpeciesPayload(model.participantProjectSpecies),
      project = ProjectPayload(model.project),
      species = SpeciesResponseElement(model.species, null),
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
          submissionStatus = submissionStatus,
      )
}
