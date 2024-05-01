package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/projects/{participantProjectSpeciesId}/species")
@RestController
class ParticipantProjectSpeciesController(
    private val participantProjectSpeciesStore: ParticipantProjectSpeciesStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{participantProjectSpeciesId}")
  @Operation(summary = "Gets information about a participant project species.")
  fun getParticipantProjectSpecies(
      @PathVariable participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): GetParticipantProjectSpeciesResponsePayload {
    val model = participantProjectSpeciesStore.fetchOneById(participantProjectSpeciesId)

    return GetParticipantProjectSpeciesResponsePayload(
        ParticipantProjectSpeciesPayload(
            feedback = model.feedback,
            id = model.id,
            projectId = model.projectId,
            rationale = model.rationale,
            speciesId = model.speciesId,
            submissionStatus = model.submissionStatus,
        ))
  }
}

data class ParticipantProjectSpeciesPayload(
    val feedback: String?,
    val id: ParticipantProjectSpeciesId,
    val projectId: ProjectId,
    val rationale: String?,
    val speciesId: SpeciesId,
    val submissionStatus: SubmissionStatus,
)

data class GetParticipantProjectSpeciesResponsePayload(
    val participantProjectSpecies: ParticipantProjectSpeciesPayload,
) : SuccessResponsePayload
