package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse403
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
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
class ProjectVotesController() {
  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @GetMapping
  @Operation(
      summary = "Gets vote selections for a single project.",
      description =
          "List every vote selection for this project, organized by phases. Each phase will " +
              "contain a list of eligible voters and their selections. ")
  fun getProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
  ): GetProjectVotesResponsePayload {
    return GetProjectVotesResponsePayload(
        ProjectVotesPayload(projectId, "testProject", emptyList()))
  }

  @ApiResponse200
  @ApiResponse403
  @ApiResponse404
  @PutMapping
  @Operation(
      summary = "Upserts vote selections for a single project.",
      description =
          "Update the user's vote for the project phase. If the (user, project, phase) does not " +
              "exist, a new entry is created. Setting a `voteOption` to `null` removes the vote.")
  fun upsertProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: UpsertProjectVotesRequestPayload,
  ): UpsertProjectVotesResponsePayload {
    return UpsertProjectVotesResponsePayload(projectId, payload.phase, emptyList())
  }

  @ApiResponse200
  @ApiResponse400
  @ApiResponse403
  @ApiResponse404
  @DeleteMapping
  @Operation(
      summary = "Remove one or more voters from the project/phase.",
      description =
          "Remove the voters from the project phase, making them ineligible from voting. This is " +
              "different from undoing a vote (by setting the `voteOption` to `null`). To remove " +
              "voters from the entire project phase, set `userId` to `null`, and set " +
              "`phaseDelete` to `true`")
  fun deleteProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: DeleteProjectVotesRequestPayload,
  ): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }
}

data class VoteSelection(
    val userId: UserId,
    @Schema(
        description =
            "The vote the user has selected. Can be yes/no/conditional or `null` if " +
                "a vote is not yet selected.")
    val email: String,
    val voteOption: VoteOption? = null,
    val conditionalInfo: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
)

data class PhaseVotes(val phase: CohortPhase, val votes: List<VoteSelection>)

data class UpsertVoteSelection(
    val userId: UserId,
    @Schema(description = "If set to `null`, remove the vote the user has previously selected.")
    val voteOption: VoteOption? = null,
    val conditionalInfo: String? = null,
)

data class UpsertProjectVotesRequestPayload(
    val phase: CohortPhase,
    val votes: List<UpsertVoteSelection>
)

data class DeleteProjectVotesRequestPayload(
    val phase: CohortPhase,
    @Schema(description = "If set to `null`, all voters in the phase will be removed. ")
    val userId: UserId? = null,
    @Schema(
        description =
            "A safeguard flag that must be set to `true` for deleting all voters in " +
                "a project phase. ")
    val phaseDelete: Boolean? = null,
)

data class ProjectVotesPayload(
    val projectId: ProjectId,
    val projectName: String,
    val phases: List<PhaseVotes>
)

data class GetProjectVotesResponsePayload(val votes: ProjectVotesPayload) : SuccessResponsePayload

data class UpsertProjectVotesResponsePayload(
    val projectId: ProjectId,
    val phase: CohortPhase,
    val results: List<UpsertVoteSelection>,
) : SuccessResponsePayload
