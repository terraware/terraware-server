package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.i18n.TimeZones
import io.swagger.v3.oas.annotations.Operation
import java.net.URI
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/projects/{projectId}/modules")
@RestController
class ProjectModulesController(
    private val clock: InstantSource,
    private val moduleStore: ModuleStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "Gets modules for a project.")
  fun listModules(
      @PathVariable projectId: ProjectId,
  ): GetProjectModulesResponsePayload {
    val models = moduleStore.fetchModulesForProject(projectId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return GetProjectModulesResponsePayload(models.map { model -> ProjectModule(model, today) })
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{moduleId}")
  @Operation(summary = "Gets one module for a project.")
  fun getModule(
      @PathVariable projectId: ProjectId,
      @PathVariable moduleId: ModuleId,
  ): GetProjectModuleResponsePayload {
    val model = moduleStore.fetchOneByIdForProject(moduleId, projectId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return GetProjectModuleResponsePayload(ProjectModule(model, today))
  }
}

data class ProjectModuleEventSession(
    val endTime: Instant?,
    val id: EventId,
    val meetingUrl: URI?,
    val recordingUrl: URI?,
    val slidesUrl: URI?,
    val startTime: Instant?,
    val status: EventStatus,
    val type: EventType,
) {
  constructor(
      model: EventModel,
  ) : this(
      endTime = model.endTime,
      id = model.id,
      meetingUrl = model.meetingUrl,
      recordingUrl = model.recordingUrl,
      slidesUrl = model.slidesUrl,
      startTime = model.startTime,
      status = model.eventStatus,
      type = model.eventType,
  )
}

data class ProjectModuleEvent(
    val description: String,
    val sessions: List<ProjectModuleEventSession>,
)

data class ProjectModule(
    val id: ModuleId,
    val title: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val isActive: Boolean,
    val additionalResources: String?,
    val overview: String?,
    val preparationMaterials: String?,
    val events: List<ProjectModuleEvent>,
) {
  constructor(
      model: ModuleModel,
      today: LocalDate,
  ) : this(
      id = model.id,
      title = model.cohorts.first().title,
      name = model.name,
      startDate = model.cohorts.first().startDate,
      endDate = model.cohorts.first().endDate,
      isActive = model.cohorts.first().isActive(today),
      additionalResources = model.additionalResources,
      overview = model.overview,
      preparationMaterials = model.preparationMaterials,
      events =
          model.eventDescriptions.map { (eventType, description) ->
            ProjectModuleEvent(
                description,
                model.eventSessions[eventType]?.map { event -> ProjectModuleEventSession(event) }
                    ?: emptyList(),
            )
          })
}

data class GetProjectModuleResponsePayload(val module: ProjectModule) : SuccessResponsePayload

data class GetProjectModulesResponsePayload(
    val modules: List<ProjectModule> = emptyList(),
) : SuccessResponsePayload
