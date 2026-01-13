package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.MODULE_EVENT_NOTIFICATION_LEAD_TIME
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
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
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
    requirePermissions { readModuleEvent(eventId) }
    return fetchById(eventId = eventId).firstOrNull() ?: throw EventNotFoundException(eventId)
  }

  fun fetchById(
      projectId: ProjectId? = null,
      moduleId: ModuleId? = null,
      eventId: EventId? = null,
      eventType: EventType? = null,
  ): List<EventModel> {
    val condition =
        DSL.and(
            projectId?.let {
              DSL.exists(
                  DSL.selectOne()
                      .from(EVENT_PROJECTS)
                      .where(EVENT_PROJECTS.PROJECT_ID.eq(it))
                      .and(EVENT_PROJECTS.EVENT_ID.eq(EVENTS.ID))
              )
            },
            moduleId?.let { EVENTS.MODULE_ID.eq(it) },
            eventId?.let { EVENTS.ID.eq(it) },
            eventType?.let { EVENTS.EVENT_TYPE_ID.eq(it) },
        )

    return fetch(condition)
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

    val eventStatus = eventStatusNow(startTime, endTime, now)

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
        if (updated.startTime == existing.startTime && updated.endTime == existing.endTime) {
          existing.revision
        } else {
          existing.revision + 1
        }

    val eventStatus = eventStatusNow(updated.startTime, updated.endTime, now)

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
        val overlap = updated.projects.intersect(existing.projects)
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

  /**
   * Return event status based on current time. Every status has a time window, and is inclusive on
   * start time and exclusive on end time of the window.
   */
  private fun eventStatusNow(startTime: Instant, endTime: Instant, now: Instant): EventStatus {
    val notifyTime = startTime.minus(MODULE_EVENT_NOTIFICATION_LEAD_TIME)
    return when {
      now.isBefore(notifyTime) -> EventStatus.NotStarted
      now.isBefore(startTime) -> EventStatus.StartingSoon
      now.isBefore(endTime) -> EventStatus.InProgress
      else -> EventStatus.Ended
    }
  }

  private fun eventProjectsMultiset(): Field<Set<ProjectId>> {
    return with(EVENT_PROJECTS) {
      DSL.multiset(DSL.select(PROJECT_ID).from(this).where(EVENT_ID.eq(EVENTS.ID))).convertFrom {
          result ->
        result.map { it.value1() }.filter { currentUser().canReadProject(it) }.toSet()
      }
    }
  }

  private fun fetch(condition: Condition?): List<EventModel> {
    val projectsField = eventProjectsMultiset()
    return with(EVENTS) {
          dslContext
              .select(
                  asterisk(),
                  MODULES.ONE_ON_ONE_SESSION_DESCRIPTION,
                  MODULES.WORKSHOP_DESCRIPTION,
                  MODULES.RECORDED_SESSION_DESCRIPTION,
                  MODULES.LIVE_SESSION_DESCRIPTION,
                  projectsField,
              )
              .from(this)
              .join(MODULES)
              .on(EVENTS.MODULE_ID.eq(MODULES.ID))
              .apply { condition?.let { where(it) } }
              .orderBy(START_TIME, END_TIME, ID)
              .fetch { EventModel.of(it, projectsField) }
        }
        .filter { currentUser().canReadModuleEvent(it.id) }
  }
}
