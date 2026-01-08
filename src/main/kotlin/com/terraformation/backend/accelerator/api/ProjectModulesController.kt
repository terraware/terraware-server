package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.CohortModuleStore
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.i18n.TimeZones
import io.swagger.v3.oas.annotations.Operation
import jakarta.ws.rs.BadRequestException
import java.time.InstantSource
import java.time.LocalDate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/projects/{projectId}/modules")
@RestController
class ProjectModulesController(
    private val clock: InstantSource,
    private val cohortModuleStore: CohortModuleStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "Lists the modules for a project.")
  fun listProjectModules(
      @PathVariable projectId: ProjectId,
  ): ListProjectModulesResponsePayload {
    val models = cohortModuleStore.fetch(projectId = projectId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return ListProjectModulesResponsePayload(
        models.map { model -> ProjectModulePayload(model, today) }
    )
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{moduleId}")
  @Operation(summary = "Gets one of a project's modules.")
  fun getProjectModule(
      @PathVariable projectId: ProjectId,
      @PathVariable moduleId: ModuleId,
  ): GetProjectModuleResponsePayload {
    val model =
        cohortModuleStore.fetch(projectId = projectId, moduleId = moduleId).firstOrNull()
            ?: throw ModuleNotFoundException(moduleId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return GetProjectModuleResponsePayload(ProjectModulePayload(model, today))
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(
      summary = "Updates the information about a module's use by a project.",
      description = "Adds the module to the project if it is not already associated.",
  )
  @PutMapping("/{moduleId}")
  fun updateProjectModule(
      @PathVariable projectId: ProjectId,
      @PathVariable moduleId: ModuleId,
      @RequestBody payload: UpdateProjectModuleRequestPayload,
  ): SimpleSuccessResponsePayload {
    if (payload.endDate < payload.startDate) {
      throw BadRequestException("Start date must be before end date")
    }

    cohortModuleStore.assign(projectId, moduleId, payload.title, payload.startDate, payload.endDate)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @DeleteMapping("/{moduleId}")
  @Operation(summary = "Deletes a module from a cohort if it is currently associated.")
  fun deleteProjectModule(
      @PathVariable projectId: ProjectId,
      @PathVariable moduleId: ModuleId,
  ): SimpleSuccessResponsePayload {
    cohortModuleStore.remove(projectId, moduleId)

    return SimpleSuccessResponsePayload()
  }
}

data class ProjectModulePayload(
    val additionalResources: String?,
    val endDate: LocalDate,
    val eventDescriptions: Map<EventType, String>,
    val id: ModuleId,
    val isActive: Boolean,
    val name: String,
    val overview: String?,
    val preparationMaterials: String?,
    val startDate: LocalDate,
    val title: String,
) {
  constructor(
      model: ModuleModel,
      today: LocalDate,
  ) : this(
      additionalResources = model.additionalResources,
      endDate = model.endDate!!,
      eventDescriptions = model.eventDescriptions,
      id = model.id,
      isActive = today in model.startDate!!..model.endDate,
      name = model.name,
      overview = model.overview,
      preparationMaterials = model.preparationMaterials,
      startDate = model.startDate,
      title = model.title!!,
  )
}

data class GetProjectModuleResponsePayload(
    val module: ProjectModulePayload,
) : SuccessResponsePayload

data class ListProjectModulesResponsePayload(
    val modules: List<ProjectModulePayload>,
) : SuccessResponsePayload

data class UpdateProjectModuleRequestPayload(
    val endDate: LocalDate,
    val startDate: LocalDate,
    val title: String,
)
