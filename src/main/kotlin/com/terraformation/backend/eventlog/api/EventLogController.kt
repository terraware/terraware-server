package com.terraformation.backend.eventlog.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.EventLogPayloadTransformer
import com.terraformation.backend.eventlog.db.EventLogStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/events")
class EventLogController(
    private val eventLogStore: EventLogStore,
    private val eventLogPayloadTransformer: EventLogPayloadTransformer,
) {
  @Operation(summary = "Lists the entries from the event log that relate to a particular entity.")
  @PostMapping("/list")
  fun listEventLogEntries(
      @RequestBody payload: ListEventLogEntriesRequestPayload
  ): ListEventLogEntriesResponsePayload {
    requirePermissions { readOrganization(payload.organizationId) }

    val eventLogEntries =
        eventLogStore.fetchByIds(
            listOfNotNull(
                payload.fileId,
                payload.monitoringPlotId,
                payload.observationId,
                payload.organizationId,
                payload.plantingSiteId,
                payload.projectId,
            ),
            requestedClasses = payload.subjects?.map { it.eventInterface }?.ifEmpty { null },
        )

    val payloads = eventLogPayloadTransformer.eventsToPayloads(eventLogEntries)

    return ListEventLogEntriesResponsePayload(payloads)
  }
}

@Schema(
    description =
        "Specifies which entities' events should be retrieved. Organization ID is mandatory, but " +
            "other IDs can also be specified here. Entities have to match all the requested IDs. " +
            "For example, if you specify observationId and monitoringPlotId, you will only get " +
            "events related to one monitoring plot in one observation, whereas if you specify " +
            "just observationId, you will get events related to all monitoring plots in that " +
            "observation. The subjects property may be used to narrow the search further."
)
data class ListEventLogEntriesRequestPayload(
    val fileId: FileId? = null,
    val monitoringPlotId: MonitoringPlotId? = null,
    val observationId: ObservationId? = null,
    val organizationId: OrganizationId,
    val plantingSiteId: PlantingSiteId? = null,
    val projectId: ProjectId? = null,
    @Schema(
        description =
            "If specified, only return event log entries for specific subject types. This can be " +
                "used to narrow the scope of the results in cases where there might be events " +
                "related to child entities and you don't care about those."
    )
    val subjects: Set<EventSubjectName>? = null,
)

data class ListEventLogEntriesResponsePayload(val events: List<EventLogEntryPayload>) :
    SuccessResponsePayload
