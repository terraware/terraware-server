package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
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
class ProjectScoresController() {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "Gets vote selections for a single project.")
  fun getProjectScores(
      @PathVariable("projectId") projectId: ProjectId,
  ): GetProjectScoresResponsePayload {
    return GetProjectScoresResponsePayload(projectId, "testProject", emptyList())
  }

  @ApiResponse200
  @ApiResponse404
  @PutMapping
  @Operation(
      summary = "Upserts vote selections for a single project.",
      description =
          "Update the scores for the project phase. If the (project, phase, category) does not " +
              "exist, a new entry is created. Setting a `score` to `null` removes the score.")
  fun upsertProjectScores(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody payload: UpsertProjectScoresRequestPayload,
  ): UpsertProjectScoresResponsePayload {
    return UpsertProjectScoresResponsePayload(projectId, payload.phase, emptyList())
  }
}

data class Score(
    val category: ScoreCategory,
    @Schema(description = "Must be between -2 to 2. If `null`, a score has not been selected.")
    val value: Int? = null,
    val qualitative: String? = null,
    val modifiedTime: Instant = Instant.now(),
)

data class UpsertScore(
    val category: ScoreCategory,
    @Schema(description = "Must be between -2 to 2. If set to `null`, remove the selected score.")
    val value: Int? = null,
    val qualitative: String? = null,
)

data class PhaseScores(
    val phase: CohortPhase,
    val scores: List<Score>,
    val systemScore: BigDecimal? = null,
)

data class UpsertProjectScoresRequestPayload(
    val phase: CohortPhase,
    val scores: List<UpsertScore>,
)

data class GetProjectScoresResponsePayload(
    val projectId: ProjectId,
    val projectName: String,
    val phases: List<PhaseScores>
) : SuccessResponsePayload

data class UpsertProjectScoresResponsePayload(
    val projectId: ProjectId,
    val phaseId: CohortPhase,
    val scores: List<Score>,
    val systemScore: BigDecimal? = null,
) : SuccessResponsePayload
