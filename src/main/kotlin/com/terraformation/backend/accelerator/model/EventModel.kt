package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class EventModel(
  val id: EventId,
  val endTime: Instant?,
  val meetingUrl: URI?,
  val projects: Set<ProjectId>? = null,
  val recordingUrl: URI?,
  val slidesUrl: URI?,
  val startTime: Instant?,
) {
  companion object {
    fun of(record: Record, projectsField: Field<Set<ProjectId>>? = null): EventModel =
        EventModel(
            id = record[EVENTS.ID]!!,
            endTime = record[EVENTS.END_TIME],
            meetingUrl = record[EVENTS.MEETING_URL],
            projects = projectsField?.let { record[it] },
            recordingUrl = record[EVENTS.RECORDING_URL],
            slidesUrl = record[EVENTS.SLIDES_URL],
            startTime = record[EVENTS.START_TIME],
        )
  }
}
