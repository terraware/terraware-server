package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.model.VoteDecisionModel
import com.terraformation.backend.accelerator.model.VoteModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse403
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.BadRequestException
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
class ProjectVotesController(
    private val voteStore: VoteStore,
) {
  @ApiResponse200
  @ApiResponse403("Attempting to read votes without sufficient privilege")
  @ApiResponse404
  @GetMapping
  @Operation(
      summary = "Gets vote selections for a single project.",
      description =
          "List every vote selection for this project, organized by phases. Each phase will " +
              "contain a list of eligible voters and their selections. ",
  )
  fun getProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
  ): GetProjectVotesResponsePayload {
    val votes = voteStore.fetchAllVotes(projectId)
    val decisions = voteStore.fetchAllVoteDecisions(projectId)
    return GetProjectVotesResponsePayload(ProjectVotesPayload(votes, decisions))
  }

  @ApiResponse200
  @ApiResponse403("Attempting to delete votes without sufficient privilege")
  @ApiResponse404
  @ApiResponse409("Attempting to upsert a vote in an inactive phase")
  @PutMapping
  @Operation(
      summary = "Upserts vote selections for a single project.",
      description =
          "Update the user's vote for the project phase. If the (user, project, phase) does not " +
              "exist, a new entry is created. Setting a `voteOption` to `null` removes the vote.",
  )
  fun upsertProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: UpsertProjectVotesRequestPayload,
  ): SimpleSuccessResponsePayload {
    payload.votes.forEach {
      voteStore.upsert(projectId, payload.phase, it.userId, it.voteOption, it.conditionalInfo)
    }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse400("Attempting to delete a phase of votes without safeguard")
  @ApiResponse403("Attempting to delete votes without sufficient privilege")
  @ApiResponse404
  @ApiResponse409("Attempting to delete a vote in an inactive phase")
  @DeleteMapping
  @Operation(
      summary = "Remove one or more voters from the project/phase.",
      description =
          "Remove the voters from the project phase, making them ineligible from voting. This is " +
              "different from undoing a vote (by setting the `voteOption` to `null`). To remove " +
              "voters from the entire project phase, set `userId` to `null`, and set " +
              "`phaseDelete` to `true`",
  )
  fun deleteProjectVotes(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: DeleteProjectVotesRequestPayload,
  ): SimpleSuccessResponsePayload {
    if (payload.userId == null && payload.phaseDelete != true) {
      throw BadRequestException(
          "Phase delete flag much be set to True for deleting all voters in a phase"
      )
    }
    voteStore.delete(projectId, payload.phase, payload.userId)
    return SimpleSuccessResponsePayload()
  }
}

data class VoteSelection(
    val conditionalInfo: String? = null,
    @Schema(
        description =
            "The vote the user has selected. Can be yes/no/conditional or `null` if " +
                "a vote is not yet selected."
    )
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val userId: UserId,
    val voteOption: VoteOption? = null,
) {
  constructor(
      model: VoteModel
  ) : this(
      conditionalInfo = model.conditionalInfo,
      email = model.email,
      firstName = model.firstName,
      lastName = model.lastName,
      userId = model.userId,
      voteOption = model.voteOption,
  )
}

data class PhaseVotes(
    val decision: VoteOption? = null,
    val phase: CohortPhase,
    val votes: List<VoteSelection> = emptyList(),
)

data class UpsertVoteSelection(
    val conditionalInfo: String? = null,
    val userId: UserId,
    @Schema(description = "If set to `null`, remove the vote the user has previously selected.")
    val voteOption: VoteOption? = null,
)

data class UpsertProjectVotesRequestPayload(
    val phase: CohortPhase,
    val votes: List<UpsertVoteSelection>,
)

data class DeleteProjectVotesRequestPayload(
    val phase: CohortPhase,
    @Schema(
        description =
            "A safeguard flag that must be set to `true` for deleting all voters in " +
                "a project phase. "
    )
    val phaseDelete: Boolean? = null,
    @Schema(description = "If set to `null`, all voters in the phase will be removed. ")
    val userId: UserId? = null,
)

data class ProjectVotesPayload(
    val phases: List<PhaseVotes>,
) {
  constructor(
      votes: List<VoteModel>,
      decisions: List<VoteDecisionModel>,
  ) : this(
      phases =
          votes
              .groupBy { it.phase }
              .mapValues {
                PhaseVotes(
                    phase = it.key,
                    decision =
                        decisions
                            .firstOrNull { decisionModel -> decisionModel.phase == it.key }
                            ?.decision,
                    votes = it.value.map { model -> VoteSelection(model) },
                )
              }
              .values
              .toList()
  )
}

data class GetProjectVotesResponsePayload(val votes: ProjectVotesPayload) : SuccessResponsePayload
