package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/modules")
@RestController
class ProjectModulesController(
    private val moduleStore: ModuleStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/projects/{projectId}")
  @Operation(summary = "Gets modules for a project.")
  fun listModules(
      @PathVariable("projectId") projectId: ProjectId,
  ): GetProjectModulesResponsePayload {
    val models = moduleStore.fetchModulesForProject(projectId)
    return GetProjectModulesResponsePayload(models)
  }
}

data class ProjectModuleEventSession(
    val id: EventId,
    // Times
    val startTime: Instant?,
    val endTime: Instant?,
    // URIs
    val meetingURL: URI?,
    val recordingURL: URI?,
    val slidesURL: URI?,
) {
  constructor(
      model: EventModel
  ) : this(
      id = model.id,
      startTime = model.startTime,
      endTime = model.endTime,
      meetingURL = model.meetingURL,
      recordingURL = model.recordingURL,
      slidesURL = model.slidesURL,
  )
}

data class ProjectModuleEvent(
    val eventDescription: String,
    val sessions: List<ProjectModuleEventSession>,
)

data class ProjectModule(
    val id: ModuleId,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    // Optional texts
    val additionalResources: String?,
    val overview: String?,
    val preparationMaterials: String?,
    // Events
    val events: Map<EventType, ProjectModuleEvent>,
) {
  constructor(
      model: ModuleModel
  ) : this(
      id = model.id,
      name = model.name,
      startDate = model.cohorts.first().startDate,
      endDate = model.cohorts.first().endDate,
      additionalResources = model.additionalResources,
      overview = model.overview,
      preparationMaterials = model.preparationMaterials,
      events =
          model.eventDescriptions.mapValues {
            ProjectModuleEvent(
                it.value,
                model.eventSessions[it.key]?.map { event -> ProjectModuleEventSession(event) }
                    ?: emptyList(),
            )
          })
}

data class GetProjectModulesResponsePayload(
    val modules: Map<CohortPhase, List<ProjectModule>> = emptyMap(),
) {
  constructor(
      models: List<ModuleModel>
  ) : this(models.groupBy { it.phase }.mapValues { entry -> entry.value.map { ProjectModule(it) } })
}
