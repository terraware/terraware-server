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
    val meetingURL: URI?,
    val projects: Set<ProjectId>? = null,
    val recordingURL: URI?,
    val slidesURL: URI?,
    val startTime: Instant?,
) {
  companion object {
    fun of(record: Record, projectsField: Field<Set<ProjectId>>? = null): EventModel =
        EventModel(
            id = record[EVENTS.ID]!!,
            endTime = record[EVENTS.END_TIME],
            meetingURL = record[EVENTS.MEETING_URL],
            projects = projectsField?.let { record[it] },
            recordingURL = record[EVENTS.RECORDING_URL],
            slidesURL = record[EVENTS.SLIDES_URL],
            startTime = record[EVENTS.START_TIME],
        )
  }
}
