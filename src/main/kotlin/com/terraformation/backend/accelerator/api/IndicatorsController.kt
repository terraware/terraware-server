package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ReportIndicatorStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.db.accelerator.CommonIndicatorId
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
  @GetMapping("/common")
  @Operation(summary = "List shared common indicator definitions.")
  fun listCommonDefinitions(): ListCommonIndicatorDefinitionsResponsePayload =
      ListCommonIndicatorDefinitionsResponsePayload(
          indicatorStore.fetchAllCommonIndicators().map { CommonIndicatorDefinitionPayload(it) }
      )

  @ApiResponse200
  @PostMapping("/common")
  @Operation(summary = "Create a shared common indicator definition.")
  fun createCommonDefinition(
      @RequestBody @Valid payload: CreateCommonIndicatorDefinitionRequestPayload
  ): CreateCommonIndicatorDefinitionResponsePayload =
      CreateCommonIndicatorDefinitionResponsePayload(
          indicatorStore.createCommonIndicator(payload.indicator.toModel())
      )

  @ApiResponse200
  @GetMapping("/projects/{projectId}/common")
  @Operation(summary = "List common indicators with project settings.")
  fun listProjectCommonIndicators(
      @PathVariable projectId: ProjectId
  ): ListCommonIndicatorsResponsePayload =
      ListCommonIndicatorsResponsePayload(
          indicatorStore.fetchCommonIndicatorsForProject(projectId).map {
            ExistingCommonIndicatorPayload(it)
          }
      )

  @ApiResponse200
  @PutMapping("/projects/{projectId}/common/{indicatorId}")
  @Operation(summary = "Update a common definition and one project's settings.")
  fun updateProjectCommonIndicator(
      @PathVariable projectId: ProjectId,
      @PathVariable indicatorId: CommonIndicatorId,
      @RequestBody @Valid payload: UpdateCommonIndicatorRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.updateCommonIndicatorForProject(
        projectId,
        indicatorId,
        payload.indicator.toModel(),
    )
    return SimpleSuccessResponsePayload()
  }

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
