package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.EventNotFoundException
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.daos.EventsDao
import com.terraformation.backend.db.accelerator.tables.pojos.EventsRow
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.EVENT_PROJECTS
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class ModuleEventStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val eventsDao: EventsDao,
) {
  fun fetchOneById(eventId: EventId): EventModel {
    requirePermissions { readModuleEventParticipants() }
    val projectsField = eventProjectsMultiset()
    return with(EVENTS) {
      dslContext.select(asterisk(), projectsField).from(this).where(ID.eq(eventId)).fetchOne {
        EventModel.of(it, projectsField)
      } ?: throw EventNotFoundException(eventId)
    }
  }

  fun fetchOneForProjectById(eventId: EventId, projectId: ProjectId): EventModel {
    requirePermissions {
      readModuleEvent(eventId)
      readProject(projectId)
    }

    return with(EVENTS) {
      dslContext
          .selectFrom(this)
          .where(ID.eq(eventId))
          .and(
              EVENTS.ID.`in`(
                  DSL.select(EVENT_PROJECTS.EVENT_ID)
                      .from(EVENT_PROJECTS)
                      .where(EVENT_PROJECTS.PROJECT_ID.eq(projectId))))
          .fetchOne { EventModel.of(it) } ?: throw EventNotFoundException(eventId)
    }
  }

  fun create(
      moduleId: ModuleId,
      eventType: EventType,
      startTime: Instant,
      endTime: Instant = startTime.plus(Duration.ofHours(1)),
      meetingUrl: URI? = null,
      recordingUrl: URI? = null,
      slidesUrl: URI? = null,
      projects: Set<ProjectId> = emptySet(),
  ): EventModel {
    requirePermissions { manageModuleEvents() }

    val now = clock.instant()
    val user = currentUser()

    val eventStatus =
        when {
          now.isBefore(startTime) -> EventStatus.NotStarted
          now.isAfter(endTime) -> EventStatus.Ended
          else -> EventStatus.InProgress
        }

    val eventsRow =
        EventsRow(
            moduleId = moduleId,
            eventStatusId = eventStatus,
            eventTypeId = eventType,
            startTime = startTime,
            endTime = endTime,
            meetingUrl = meetingUrl,
            recordingUrl = recordingUrl,
            revision = 1,
            slidesUrl = slidesUrl,
            createdBy = user.userId,
            createdTime = now,
            modifiedBy = user.userId,
            modifiedTime = now,
        )

    eventsDao.insert(eventsRow)

    with(EVENT_PROJECTS) {
      projects.forEach {
        dslContext.insertInto(this, EVENT_ID, PROJECT_ID).values(eventsRow.id, it).execute()
      }
    }

    val model = eventsRow.toModel(projects)
    eventPublisher.publishEvent(ModuleEventScheduledEvent(model.id, model.revision))

    return model
  }

  fun delete(eventId: EventId) {
    requirePermissions { manageModuleEvents() }

    val rowsDeleted = with(EVENTS) { dslContext.deleteFrom(this).where(ID.eq(eventId)).execute() }

    if (rowsDeleted == 0) {
      throw EventNotFoundException(eventId)
    }
  }

  /** Update event details including times, participants and URLs. */
  fun updateEvent(eventId: EventId, updateFunc: (EventModel) -> EventModel) {
    requirePermissions { manageModuleEvents() }

    val existing = fetchOneById(eventId)
    val updated = updateFunc(existing)
    val userId = currentUser().userId
    val now = clock.instant()

    val revision =
        if (updated.startTime == existing.startTime) {
          existing.revision
        } else {
          existing.revision + 1
        }

    val eventStatus =
        when {
          now.isBefore(updated.startTime) -> EventStatus.NotStarted
          now.isAfter(updated.endTime) -> EventStatus.Ended
          else -> EventStatus.InProgress
        }

    dslContext.transaction { _ ->
      val rowsUpdated =
          with(EVENTS) {
            dslContext
                .update(this)
                .set(MEETING_URL, updated.meetingUrl)
                .set(RECORDING_URL, updated.recordingUrl)
                .set(SLIDES_URL, updated.slidesUrl)
                .set(START_TIME, updated.startTime)
                .set(END_TIME, updated.endTime)
                .set(EVENT_STATUS_ID, eventStatus)
                .set(REVISION, revision)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .where(ID.eq(eventId))
                .execute()
          }

      if (rowsUpdated < 1) {
        throw EventNotFoundException(eventId)
      }

      if (updated.projects != existing.projects) {
        val overlap = updated.projects!!.intersect(existing.projects!!)
        val toAdd = updated.projects.minus(overlap)
        val toRemove = existing.projects.minus(overlap)

        with(EVENT_PROJECTS) {
          if (toRemove.isNotEmpty()) {
            dslContext
                .deleteFrom(this)
                .where(EVENT_ID.eq(eventId))
                .and(PROJECT_ID.`in`(toRemove))
                .execute()
          }

          toAdd.forEach {
            dslContext.insertInto(this, EVENT_ID, PROJECT_ID).values(eventId, it).execute()
          }
        }
      }
    }

    if (updated.startTime != existing.startTime || updated.endTime != existing.endTime) {
      eventPublisher.publishEvent(ModuleEventScheduledEvent(eventId, revision))
    }
  }

  /**
   * Update event status. Primarily used by job scheduler to update event status at scheduled times.
   */
  fun updateEventStatus(eventId: EventId, status: EventStatus) {
    requirePermissions { manageModuleEventStatuses() }

    val rowsUpdated =
        with(EVENTS) {
          dslContext.update(this).set(EVENT_STATUS_ID, status).where(ID.eq(eventId)).execute()
        }

    if (rowsUpdated < 1) {
      throw EventNotFoundException(eventId)
    }
  }
}
