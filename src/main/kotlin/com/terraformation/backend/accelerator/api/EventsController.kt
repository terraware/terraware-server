package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import java.net.URI
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/events")
@RestController
class EventsController(
    private val eventStore: ModuleEventStore,
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
    return ListEventsResponsePayload(models.map { model -> ModuleEvent(model) })
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{eventId}")
  @Operation(summary = "Gets one event for a project.")
  fun getEvent(
      @PathVariable eventId: EventId,
  ): GetEventResponsePayload {
    val model = eventStore.fetchOneById(eventId)
    return GetEventResponsePayload(ModuleEvent(model))
  }
}

data class ModuleEvent(
    val description: String?,
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
      description = model.description,
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

data class GetEventResponsePayload(
    val event: ModuleEvent,
) : SuccessResponsePayload

data class ListEventsResponsePayload(
    val events: List<ModuleEvent>,
) : SuccessResponsePayload
