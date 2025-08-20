package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ProjectScoreStore
import com.terraformation.backend.accelerator.model.NewProjectScoreModel
import com.terraformation.backend.accelerator.model.ProjectScoreModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.math.BigDecimal
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/projects/{projectId}/scores")
@RestController
class ProjectScoresController(
    private val projectScoreStore: ProjectScoreStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "Gets score selections for a single project.")
  fun getProjectScores(
      @PathVariable("projectId") projectId: ProjectId,
  ): GetProjectScoresResponsePayload {
    val scoresByPhase = projectScoreStore.fetchScores(projectId)

    val phaseScores =
        scoresByPhase.map { (phase, scoreModels) ->
          PhaseScores(
              phase,
              scoreModels.map { Score(it.category, it.modifiedTime, it.qualitative, it.score) },
              ProjectScoreModel.totalScore(phase, scoreModels),
          )
        }

    return GetProjectScoresResponsePayload(phaseScores)
  }

  @ApiResponse200
  @ApiResponse404
  @PutMapping
  @Operation(
      summary = "Upserts score selections for a single project.",
      description =
          "Update the scores for the project phase. If the (project, phase, category) does not " +
              "exist, a new entry is created. Setting a `score` to `null` removes the score.",
  )
  fun upsertProjectScores(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody @Valid payload: UpsertProjectScoresRequestPayload,
  ): SimpleSuccessResponsePayload {
    projectScoreStore.updateScores(projectId, payload.phase, payload.scores.map { it.toModel() })

    return SimpleSuccessResponsePayload()
  }
}

data class Score(
    val category: ScoreCategory,
    val modifiedTime: Instant,
    val qualitative: String? = null,
    @Schema(
        description = "If `null`, a score has not been selected.",
        minimum = "-2",
        maximum = "2",
    )
    val value: Int? = null,
)

data class UpsertScore(
    val category: ScoreCategory,
    val qualitative: String? = null,
    @field:Min(-2)
    @field:Max(2)
    @Schema(
        description = "If set to `null`, remove the selected score.",
        minimum = "-2",
        maximum = "2",
    )
    val value: Int? = null,
) {
  fun toModel() = NewProjectScoreModel(category, null, qualitative, value)
}

data class PhaseScores(
    val phase: CohortPhase,
    val scores: List<Score>,
    val totalScore: BigDecimal? = null,
)

data class UpsertProjectScoresRequestPayload(
    val phase: CohortPhase,
    @field:Valid val scores: List<UpsertScore>,
)

data class GetProjectScoresResponsePayload(val phases: List<PhaseScores>) : SuccessResponsePayload
