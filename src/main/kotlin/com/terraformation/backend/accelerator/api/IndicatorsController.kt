package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ReportIndicatorStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/indicators")
@RestController
class IndicatorsController(private val indicatorStore: ReportIndicatorStore) {

  @ApiResponse200
  @GetMapping("/projects/{projectId}/project")
  @Operation(summary = "List project-specific indicators.")
  fun listProjectSpecificIndicators(
      @PathVariable projectId: ProjectId
  ): ListProjectIndicatorsResponsePayload =
      ListProjectIndicatorsResponsePayload(
          indicatorStore.fetchProjectIndicatorsForProject(projectId).map {
            ExistingProjectIndicatorPayload(it)
          }
      )

  @ApiResponse200
  @PostMapping("/projects/{projectId}/project")
  @Operation(summary = "Create a project-specific indicator.")
  fun createProjectSpecificIndicator(
      @PathVariable projectId: ProjectId,
      @RequestBody @Valid payload: CreateProjectIndicatorRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.createProjectIndicator(payload.indicator.toProjectIndicatorModel(projectId))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @PutMapping("/projects/{projectId}/project/{indicatorId}")
  @Operation(summary = "Update a project-specific indicator.")
  fun updateProjectSpecificIndicator(
      @PathVariable projectId: ProjectId,
      @PathVariable indicatorId: ProjectIndicatorId,
      @RequestBody @Valid payload: UpdateProjectIndicatorRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.updateProjectIndicator(indicatorId, projectId) { payload.indicator.toModel() }
    return SimpleSuccessResponsePayload()
  }
}
