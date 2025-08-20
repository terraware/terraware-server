package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ProjectOverallScoreStore
import com.terraformation.backend.accelerator.model.ProjectOverallScoreModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import java.net.URI
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v2/accelerator/projects/{projectId}/scores")
@RestController
class ProjectScoresControllerV2(
    private val projectOverallScoreStore: ProjectOverallScoreStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "Gets overall score for a single project. ")
  fun getProjectOverallScore(
      @PathVariable("projectId") projectId: ProjectId,
  ): GetProjectOverallScoreResponsePayload {
    val model = projectOverallScoreStore.fetch(projectId)
    return GetProjectOverallScoreResponsePayload(ProjectOverallScorePayload(model))
  }

  @ApiResponse200
  @ApiResponse404
  @PutMapping
  @Operation(summary = "Updates overall score for a single project.")
  fun upsertProjectScores(
      @PathVariable("projectId") projectId: ProjectId,
      @RequestBody @Valid payload: UpdateProjectOverallScoreRequestPayload,
  ): SimpleSuccessResponsePayload {
    projectOverallScoreStore.update(projectId) { payload.score.toModel(projectId) }

    return SimpleSuccessResponsePayload()
  }
}

data class UpdateProjectOverallScorePayload(
    val detailsUrl: URI?,
    val overallScore: Double?,
    val summary: String?,
) {
  fun toModel(projectId: ProjectId) =
      ProjectOverallScoreModel(
          detailsUrl,
          overallScore,
          projectId,
          summary,
      )
}

data class ProjectOverallScorePayload(
    val detailsUrl: URI?,
    val overallScore: Double?,
    val summary: String?,
    val modifiedBy: UserId?,
    val modifiedTime: Instant?,
) {
  constructor(
      model: ProjectOverallScoreModel
  ) : this(
      model.detailsUrl,
      model.overallScore,
      model.summary,
      model.modifiedBy,
      model.modifiedTime,
  )
}

data class UpdateProjectOverallScoreRequestPayload(
    val score: UpdateProjectOverallScorePayload,
)

data class GetProjectOverallScoreResponsePayload(
    val score: ProjectOverallScorePayload,
) : SuccessResponsePayload
