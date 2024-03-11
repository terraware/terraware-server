package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse403
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/voting/")
@RestController
class ProjectVotingController() {
  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @GetMapping("/{projectId}")
  @Operation(summary = "Gets vote selections for a single project.")
  fun getProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
  ): ProjectVotesResponsePayload {
    return ProjectVotesResponsePayload(ProjectVotesPayload(projectId, listOf()))
  }

  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @PostMapping("/{projectId}")
  @Operation(summary = "Upserts vote selections for a single project.")
  fun upsertProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: UpsertProjectVotesRequestPayload,
  ): ProjectVotesResponsePayload {
    return ProjectVotesResponsePayload(ProjectVotesPayload(projectId, listOf()))
  }

  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @DeleteMapping("/{projectId}")
  @Operation(
      summary = "Delete voters that match the query options from the project.")
  fun deleteProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: DeleteProjectVotesRequestPayload,
  ): ProjectVotesResponsePayload {
    return ProjectVotesResponsePayload(ProjectVotesPayload(projectId, listOf()))
  }
}

data class VoteSelection(
    val user: UserId,
    val voteOption: VoteOption? = null,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
)

data class CohortVotes(val cohortPhase: CohortPhase, val votes: List<VoteSelection>)

data class ProjectVotesPayload(val projectId: ProjectId, val phases: List<CohortVotes>)

data class UpsertVoteSelection(
    val projectId: ProjectId,
    val phase: CohortPhase,
    val user: UserId,
    val voteOption: VoteOption? = null
)

data class UpsertProjectVotesRequestPayload(val votes: List<UpsertVoteSelection>)

data class DeleteVoteSelection(
    val projectId: ProjectId,
    val phase: CohortPhase? = null,
    val userId: UserId? = null
)

data class DeleteProjectVotesRequestPayload(val options: List<DeleteVoteSelection>)

data class ProjectVotesResponsePayload(val votes: ProjectVotesPayload) : SuccessResponsePayload
