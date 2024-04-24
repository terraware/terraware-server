package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.pojos.EventsRow
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class EventModel(
    val id: EventId,
    val endTime: Instant?,
    val eventType: EventType,
    val meetingUrl: URI?,
    val moduleId: ModuleId,
    val projects: Set<ProjectId>? = null,
    val recordingUrl: URI?,
    val revision: Int,
    val slidesUrl: URI?,
    val startTime: Instant?,
) {
  companion object {
    fun of(record: Record, projectsField: Field<Set<ProjectId>>? = null): EventModel =
        EventModel(
            id = record[EVENTS.ID]!!,
            endTime = record[EVENTS.END_TIME],
            eventType = record[EVENTS.EVENT_TYPE_ID]!!,
            meetingUrl = record[EVENTS.MEETING_URL],
            moduleId = record[EVENTS.MODULE_ID]!!,
            projects = projectsField?.let { record[it] },
            recordingUrl = record[EVENTS.RECORDING_URL],
            revision = record[EVENTS.REVISION]!!,
            slidesUrl = record[EVENTS.SLIDES_URL],
            startTime = record[EVENTS.START_TIME],
        )
  }
}

fun EventsRow.toModel(projectIds: Set<ProjectId> = emptySet()): EventModel {
  return EventModel(
      id = id!!,
      endTime = endTime,
      eventType = eventTypeId!!,
      meetingUrl = meetingUrl,
      moduleId = moduleId!!,
      projects = projectIds,
      recordingUrl = recordingUrl,
      revision = revision!!,
      slidesUrl = slidesUrl,
      startTime = startTime,
  )
}
