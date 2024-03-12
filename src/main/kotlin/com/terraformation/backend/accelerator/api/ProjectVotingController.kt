package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse403
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/projects/{projectId}/votes")
@RestController
class ProjectVotingController() {
  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @GetMapping
  @Operation(
      summary = "Gets vote selections for a single project.",
      description =
          "List every vote selection for this project, organized by phases. Each phase will " +
              "contain a list of eligible voters and their selections. If a `voteOption` is " +
              "`null`, the voter has not yet casted a vote. ")
  fun getProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
  ): GetProjectVotesResponsePayload {
    return GetProjectVotesResponsePayload(projectId, emptyList())
  }

  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @PutMapping
  @Operation(
      summary = "Upserts vote selections for a single project.",
      description =
          "Update the user's vote for the project phase. If the (user, project, phase) does not, " +
              "exist, a new entry is created. If a `voteOption` is set to `null`, the voter has " +
              "not yet casted a vote. ")
  fun upsertProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: UpsertProjectVotesRequestPayload,
  ): UpsertProjectVotesResponsePayload {
    return UpsertProjectVotesResponsePayload(projectId, emptyList())
  }

  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @DeleteMapping
  @Operation(
      summary = "Remove one or more voters from the project/phase.",
      description =
          "Delete the voters from the project phase, making them ineligible from voting. This is " +
              "different from undoing a vote (by setting the `voteOption` to `null`). `projectId`" +
              "must be provided. If neither `phaseId` or `userId` is provided, all voters from " +
              "every phase are removed. If only `phaseId` is provided, all voters from that " +
              "phase are removed. If only `userId` is provided, the voter is removed from every " +
              "phase of this project. If both `userId` and `phaseId` are provided, the voter is " +
              "removed from that phase.")
  fun deleteProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: DeleteProjectVotesRequestPayload,
  ): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }
}

data class VoteSelection(
    val userId: UserId,
    val voteOption: VoteOption? = null,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
)

data class PhaseVotes(val phase: CohortPhase, val votes: List<VoteSelection>)

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

data class GetProjectVotesResponsePayload(val projectId: ProjectId, val phases: List<PhaseVotes>) :
    SuccessResponsePayload

data class UpsertProjectVotesResponsePayload(
    val projectId: ProjectId,
    val results: List<UpsertVoteSelection>
) : SuccessResponsePayload
