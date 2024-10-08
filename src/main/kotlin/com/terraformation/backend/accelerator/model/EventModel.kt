package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.pojos.EventsRow
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class EventModel(
    val id: EventId,
    val description: String? = null,
    val endTime: Instant,
    val eventStatus: EventStatus,
    val eventType: EventType,
    val meetingUrl: URI? = null,
    val moduleId: ModuleId,
    val projects: Set<ProjectId>,
    val recordingUrl: URI? = null,
    val revision: Int,
    val slidesUrl: URI? = null,
    val startTime: Instant,
) {
  companion object {
    fun of(record: Record, projectsField: Field<Set<ProjectId>>): EventModel {
      val eventType = record[EVENTS.EVENT_TYPE_ID]!!
      val description =
          when (eventType) {
            EventType.Workshop -> record[MODULES.WORKSHOP_DESCRIPTION]
            EventType.LiveSession -> record[MODULES.LIVE_SESSION_DESCRIPTION]
            EventType.OneOnOneSession -> record[MODULES.ONE_ON_ONE_SESSION_DESCRIPTION]
            EventType.RecordedSession -> record[MODULES.RECORDED_SESSION_DESCRIPTION]
          }
      return EventModel(
          id = record[EVENTS.ID]!!,
          description = description,
          endTime = record[EVENTS.END_TIME]!!,
          eventType = eventType,
          eventStatus = record[EVENTS.EVENT_STATUS_ID]!!,
          meetingUrl = record[EVENTS.MEETING_URL],
          moduleId = record[EVENTS.MODULE_ID]!!,
          projects = record[projectsField]!!,
          recordingUrl = record[EVENTS.RECORDING_URL],
          revision = record[EVENTS.REVISION]!!,
          slidesUrl = record[EVENTS.SLIDES_URL],
          startTime = record[EVENTS.START_TIME]!!,
      )
    }
  }
}

fun EventsRow.toModel(projectIds: Set<ProjectId> = emptySet()): EventModel {
  return EventModel(
      id = id!!,
      endTime = endTime!!,
      eventStatus = eventStatusId!!,
      eventType = eventTypeId!!,
      meetingUrl = meetingUrl,
      moduleId = moduleId!!,
      projects = projectIds,
      recordingUrl = recordingUrl,
      revision = revision!!,
      slidesUrl = slidesUrl,
      startTime = startTime!!,
  )
}
