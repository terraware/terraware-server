package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.AcceleratorProjectService
import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.model.AcceleratorProjectModel
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import jakarta.ws.rs.BadRequestException
import java.net.URI
import java.time.Instant
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/events")
@RestController
class EventsController(
    private val acceleratorProjectService: AcceleratorProjectService,
    private val eventStore: ModuleEventStore,
    private val moduleStore: ModuleStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "List events")
  fun listEvents(
      @RequestParam projectId: ProjectId?,
      @RequestParam moduleId: ModuleId?,
  ): ListEventsResponsePayload {
    val models = eventStore.fetchById(projectId = projectId, moduleId = moduleId)
    return ListEventsResponsePayload(
        models.map { model ->
          val projects =
              if (currentUser().canReadModuleEventParticipants()) {
                model.projects.map {
                  ModuleEventProject(acceleratorProjectService.fetchOneById(it))
                }
              } else {
                null
              }
          ModuleEvent(model, moduleStore.fetchOneById(model.moduleId).name, projects)
        }
    )
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{eventId}")
  @Operation(summary = "Gets one event for a project.")
  fun getEvent(
      @PathVariable eventId: EventId,
  ): GetEventResponsePayload {
    val model = eventStore.fetchOneById(eventId)
    val moduleName = moduleStore.fetchOneById(model.moduleId).name

    val projects =
        if (currentUser().canReadModuleEventParticipants()) {
          model.projects.map { ModuleEventProject(acceleratorProjectService.fetchOneById(it)) }
        } else {
          null
        }

    return GetEventResponsePayload(ModuleEvent(model, moduleName, projects))
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(
      summary = "Create a new event on a module.",
      description = "Only accessible by accelerator administrators.",
  )
  @PostMapping
  @RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun createEvent(
      @RequestBody payload: CreateModuleEventRequestPayload,
  ): CreateModuleEventResponsePayload {
    if (payload.endTime == null && payload.eventType != EventType.RecordedSession) {
      throw BadRequestException("End time is required for this event type")
    }

    val event =
        eventStore.create(
            payload.moduleId,
            payload.eventType,
            payload.startTime,
            payload.endTime ?: payload.startTime,
            payload.meetingUrl,
            payload.recordingUrl,
            payload.slidesUrl,
        )

    return CreateModuleEventResponsePayload(event.id)
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(
      summary = "Update an event on a module.",
      description = "Only accessible by accelerator administrators.",
  )
  @PutMapping("/{eventId}")
  @RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun updateEvent(
      @PathVariable eventId: EventId,
      @RequestBody payload: UpdateModuleEventRequestPayload,
  ): SimpleSuccessResponsePayload {
    eventStore.updateEvent(eventId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @DeleteMapping("/{eventId}")
  @Operation(
      summary = "Delete an event from a module.",
      description = "Only accessible by accelerator administrators.",
  )
  @RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun deleteEvent(
      @PathVariable eventId: EventId,
  ): SimpleSuccessResponsePayload {
    eventStore.delete(eventId)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(
      summary = "Update the list of projects for a module event.",
      description = "Only accessible by accelerator administrators.",
  )
  @PostMapping("/{eventId}/projects")
  @RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun updateEventProjects(
      @PathVariable eventId: EventId,
      @RequestBody payload: UpdateModuleEventProjectsRequestPayload,
  ): SimpleSuccessResponsePayload {
    eventStore.updateEvent(eventId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class ModuleEventProject(
    val projectId: ProjectId,
    val projectName: String,
    val participantId: ParticipantId,
    val participantName: String,
    val cohortId: CohortId,
    val cohortName: String,
) {
  constructor(
      model: AcceleratorProjectModel
  ) : this(
      model.projectId,
      model.projectName,
      model.participantId,
      model.participantName,
      model.cohortId,
      model.cohortName,
  )
}

data class ModuleEvent(
    val description: String?,
    val endTime: Instant?,
    val id: EventId,
    val meetingUrl: URI?,
    val moduleId: ModuleId,
    val moduleName: String,
    val projects: List<ModuleEventProject>?,
    val recordingUrl: URI?,
    val slidesUrl: URI?,
    val startTime: Instant?,
    val status: EventStatus,
    val type: EventType,
) {
  constructor(
      model: EventModel,
      moduleName: String,
      projects: List<ModuleEventProject>? = null,
  ) : this(
      description = model.description,
      endTime = model.endTime,
      id = model.id,
      meetingUrl = model.meetingUrl,
      moduleId = model.moduleId,
      moduleName = moduleName,
      projects = projects,
      recordingUrl = model.recordingUrl,
      slidesUrl = model.slidesUrl,
      startTime = model.startTime,
      status = model.eventStatus,
      type = model.eventType,
  )
}

data class GetEventResponsePayload(
    val event: ModuleEvent,
) : SuccessResponsePayload

data class CreateModuleEventRequestPayload(
    val endTime: Instant?,
    val eventType: EventType,
    val meetingUrl: URI?,
    val moduleId: ModuleId,
    val recordingUrl: URI?,
    val slidesUrl: URI?,
    val startTime: Instant,
)

data class UpdateModuleEventProjectsRequestPayload(
    val addProjects: Set<ProjectId>? = null,
    val removeProjects: Set<ProjectId>? = null,
) {
  fun applyTo(event: EventModel): EventModel {
    val projects = event.projects.toMutableSet()
    addProjects?.let { projects.addAll(it) }
    removeProjects?.let { projects.removeAll(it) }

    return event.copy(projects = projects)
  }
}

data class UpdateModuleEventRequestPayload(
    val endTime: Instant?,
    val meetingUrl: URI?,
    val recordingUrl: URI?,
    val slidesUrl: URI?,
    val startTime: Instant,
) {
  fun applyTo(model: EventModel): EventModel =
      model.copy(
          endTime = endTime ?: startTime,
          startTime = startTime,
          meetingUrl = meetingUrl,
          recordingUrl = recordingUrl,
          slidesUrl = slidesUrl,
      )
}

data class CreateModuleEventResponsePayload(val id: EventId) : SuccessResponsePayload

data class ListEventsResponsePayload(
    val events: List<ModuleEvent>,
) : SuccessResponsePayload
