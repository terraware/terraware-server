package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.MODULE_EVENT_NOTIFICATION_LEAD_TIME
import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EventNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.pojos.EventProjectsRow
import com.terraformation.backend.db.accelerator.tables.pojos.EventsRow
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ModuleEventStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val store: ModuleEventStore by lazy {
    ModuleEventStore(clock, dslContext, eventPublisher, eventsDao)
  }
  private lateinit var moduleId: ModuleId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
    moduleId =
        insertModule(
            liveSessionDescription = "Live session description",
            workshopDescription = "Workshop description",
        )
    insertProjectModule()

    every { user.canManageModules() } returns true
    every { user.canManageModuleEvents() } returns true
    every { user.canReadModuleEvent(any()) } returns true
    every { user.canReadModuleEventParticipants() } returns true
    every { user.canReadProjectModules(any()) } returns true
    every { user.canReadProject(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `throws exception if event does not exist`() {
      assertThrows<EventNotFoundException> { store.fetchOneById(EventId(-1)) }
    }

    @Test
    fun `throws exception no permission to view event`() {
      every { user.canReadModuleEvent(any()) } returns false
      val eventId = insertEvent()
      assertThrows<EventNotFoundException> { store.fetchOneById(eventId) }
    }

    @Test
    fun `returns event with visible participants`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val endTime = startTime.plusSeconds(3600)
      val project1 = projectId
      val project2 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val invisibleProject = insertProject(phase = CohortPhase.Phase0DueDiligence)

      val workshop =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              meetingUrl = "https://meeting.com",
              slidesUrl = "https://slides.com",
              recordingUrl = "https://recording.com",
              startTime = startTime,
              endTime = endTime,
          )

      insertEventProject(workshop, project1)
      insertEventProject(workshop, project2)
      insertEventProject(workshop, invisibleProject)

      every { user.canReadProject(invisibleProject) } returns false

      assertEquals(
          EventModel(
              id = workshop,
              description = "Workshop description",
              eventStatus = EventStatus.NotStarted,
              eventType = EventType.Workshop,
              moduleId = moduleId,
              meetingUrl = URI("https://meeting.com"),
              slidesUrl = URI("https://slides.com"),
              recordingUrl = URI("https://recording.com"),
              revision = 1,
              startTime = startTime,
              endTime = endTime,
              projects = setOf(project1, project2),
          ),
          store.fetchOneById(workshop),
      )
    }
  }

  @Nested
  inner class FetchById {
    @Test
    fun `queries by IDs`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val time1 = clock.instant.plusSeconds(3600)
      val time2 = time1.plusSeconds(3600)
      val time3 = time2.plusSeconds(3600)
      val time4 = time3.plusSeconds(3600)

      val project1 = projectId
      val project2 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val invisibleProject = insertProject(phase = CohortPhase.Phase0DueDiligence)

      val otherModule = insertModule(oneOnOneSessionDescription = "1:1 description")
      insertProjectModule(projectId = project1)
      insertProjectModule(projectId = project2)

      every { user.canReadProject(invisibleProject) } returns false

      val eventId1 =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              startTime = time1,
              endTime = time2,
          )

      val eventId2 =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.LiveSession,
              startTime = time2,
              endTime = time3,
          )

      val eventId3 =
          insertEvent(
              moduleId = otherModule,
              eventType = EventType.OneOnOneSession,
              startTime = time3,
              endTime = time4,
          )

      insertEventProject(eventId1, project1)
      insertEventProject(eventId1, project2)
      insertEventProject(eventId1, invisibleProject)

      insertEventProject(eventId2, project1)
      insertEventProject(eventId2, invisibleProject)

      insertEventProject(eventId3, project2)
      insertEventProject(eventId3, invisibleProject)

      val event1 =
          EventModel(
              id = eventId1,
              description = "Workshop description",
              eventStatus = EventStatus.NotStarted,
              eventType = EventType.Workshop,
              moduleId = moduleId,
              revision = 1,
              startTime = time1,
              endTime = time2,
              projects = setOf(project1, project2),
          )

      val event2 =
          EventModel(
              id = eventId2,
              description = "Live session description",
              eventStatus = EventStatus.NotStarted,
              eventType = EventType.LiveSession,
              moduleId = moduleId,
              revision = 1,
              startTime = time2,
              endTime = time3,
              projects = setOf(project1),
          )

      val event3 =
          EventModel(
              id = eventId3,
              description = "1:1 description",
              eventStatus = EventStatus.NotStarted,
              eventType = EventType.OneOnOneSession,
              moduleId = otherModule,
              revision = 1,
              startTime = time3,
              endTime = time4,
              projects = setOf(project2),
          )

      assertEquals(listOf(event1, event2, event3), store.fetchById(), "Fetch all")
      assertEquals(
          listOf(event1, event2),
          store.fetchById(projectId = project1),
          "Fetch by project ID",
      )
      assertEquals(listOf(event3), store.fetchById(moduleId = otherModule), "Fetch by module ID")
      assertEquals(listOf(event2), store.fetchById(eventId = eventId2), "Fetch by Event ID")

      assertEquals(
          listOf(event1),
          store.fetchById(projectId = project2, moduleId = moduleId),
          "Fetch by projectId and module ID",
      )
      assertEquals(
          listOf(event3),
          store.fetchById(projectId = project2, eventId = eventId3),
          "Fetch by projectId and event ID",
      )

      assertEquals(
          listOf(event3),
          store.fetchById(eventType = EventType.OneOnOneSession),
          "Fetch by eventTypeId",
      )
    }
  }

  @Nested
  inner class Create {
    @Test
    fun `creates event and publishes event scheduled event`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)

      val model = store.create(moduleId, EventType.Workshop, startTime)

      assertEquals(
          listOf(
              EventsRow(
                  id = model.id,
                  moduleId = moduleId,
                  eventStatusId = EventStatus.NotStarted,
                  eventTypeId = EventType.Workshop,
                  revision = 1,
                  startTime = startTime,
                  endTime = startTime.plus(Duration.ofHours(1)), // Default event length of one hour
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          eventsDao.findAll(),
      )

      eventPublisher.assertEventPublished(ModuleEventScheduledEvent(model.id, model.revision))
    }

    @Test
    fun `creates event and set event status based on time`() {
      clock.instant = Instant.EPOCH.plusSeconds(3600)
      val threeSecondsAgo = clock.instant.minusSeconds(3)
      val twoSecondsAgo = clock.instant.minusSeconds(2)
      val now = clock.instant
      val twoSecondsLater = clock.instant.plusSeconds(2)
      val threeSecondsLater = clock.instant.plusSeconds(3)
      val leadDurationLater = clock.instant.plus(MODULE_EVENT_NOTIFICATION_LEAD_TIME)
      val leadDurationAndTwoSecondsLater = leadDurationLater.plusSeconds(2)
      val leadDurationAndThreeSecondsLater = leadDurationLater.plusSeconds(3)

      val endedEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = threeSecondsAgo,
              endTime = twoSecondsAgo,
          )
      val endingEvent =
          store.create(moduleId, EventType.Workshop, startTime = twoSecondsAgo, endTime = now)
      val inProgressEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = twoSecondsAgo,
              endTime = twoSecondsLater,
          )
      val startingEvent =
          store.create(moduleId, EventType.Workshop, startTime = now, endTime = twoSecondsLater)
      val startingInSecondsEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = twoSecondsLater,
              endTime = threeSecondsLater,
          )
      val startingSoonEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = leadDurationLater,
              endTime = leadDurationAndTwoSecondsLater,
          )
      val futureEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = leadDurationAndTwoSecondsLater,
              endTime = leadDurationAndThreeSecondsLater,
          )

      val results = eventsDao.findAll()
      val statuses = results.associate { it.id to it.eventStatusId }

      assertEquals(statuses[endedEvent.id], EventStatus.Ended, "end < now events has Ended status")
      assertEquals(statuses[endingEvent.id], EventStatus.Ended, "end = now events has Ended status")
      assertEquals(
          statuses[inProgressEvent.id],
          EventStatus.InProgress,
          "start < now < end events has InProgress status",
      )
      assertEquals(
          statuses[startingEvent.id],
          EventStatus.InProgress,
          "now = start events has InProgress status",
      )
      assertEquals(
          statuses[startingInSecondsEvent.id],
          EventStatus.StartingSoon,
          "start < now < notify events has StartingSoon status",
      )
      assertEquals(
          statuses[startingSoonEvent.id],
          EventStatus.StartingSoon,
          "now = notify events has StartingSoon status",
      )
      assertEquals(
          statuses[futureEvent.id],
          EventStatus.NotStarted,
          "notify < now events has NotStarted status",
      )
    }

    @Test
    fun `creates event project rows when projects are provided`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val project1 = projectId
      val project2 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val model =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime,
              projects = setOf(project1, project2),
          )

      assertNotNull(eventsDao.fetchOneById(model.id))

      assertSetEquals(
          setOf(
              EventProjectsRow(model.id, project1),
              EventProjectsRow(model.id, project2),
          ),
          eventProjectsDao.findAll().toSet(),
      )
    }

    @Test
    fun `throws exception if no permission to manage events`() {
      every { user.canManageModuleEvents() } returns false

      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      assertThrows<AccessDeniedException> { store.create(moduleId, EventType.Workshop, startTime) }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `throws exception if event does not exist`() {
      assertThrows<EventNotFoundException> { store.updateEvent(EventId(-1)) { it } }
    }

    @Test
    fun `throws exception if no permission to manage events`() {
      every { user.canManageModuleEvents() } returns false

      val eventId = insertEvent()
      assertThrows<AccessDeniedException> { store.updateEvent(eventId) { it } }
    }

    @Test
    fun `updates event without changing start time will not update revision`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val endTime = startTime.plusSeconds(3600)

      val workshop =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              meetingUrl = "https://meeting.com",
              slidesUrl = "https://slides.com",
              recordingUrl = "https://recording.com",
              startTime = startTime,
              endTime = endTime,
          )

      assertEquals(
          EventsRow(
              id = workshop,
              moduleId = moduleId,
              eventStatusId = EventStatus.NotStarted,
              eventTypeId = EventType.Workshop,
              meetingUrl = URI("https://meeting.com"),
              slidesUrl = URI("https://slides.com"),
              recordingUrl = URI("https://recording.com"),
              revision = 1,
              startTime = startTime,
              endTime = endTime,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          ),
          eventsDao.findById(workshop),
      )

      store.updateEvent(workshop) { it.copy(meetingUrl = URI("https://newmeeting.com")) }

      assertEquals(
          EventsRow(
              id = workshop,
              moduleId = moduleId,
              eventStatusId = EventStatus.NotStarted,
              eventTypeId = EventType.Workshop,
              meetingUrl = URI("https://newmeeting.com"),
              slidesUrl = URI("https://slides.com"),
              recordingUrl = URI("https://recording.com"),
              revision = 1,
              startTime = startTime,
              endTime = endTime,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          ),
          eventsDao.findById(workshop),
      )

      eventPublisher.assertEventNotPublished<ModuleEventScheduledEvent>()
    }

    @Test
    fun `updates event ignores event id, revision or status updates`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val endTime = startTime.plusSeconds(3600)

      val workshop =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              startTime = startTime,
              endTime = endTime,
          )

      val original = eventsDao.findById(workshop)

      store.updateEvent(workshop) {
        it.copy(id = EventId(100), eventStatus = EventStatus.Ended, revision = 10)
      }

      val updated = eventsDao.findById(workshop)
      assertEquals(original!!.copy(modifiedTime = clock.instant), updated)
    }

    @Test
    fun `increments revision and publishes event if start time updated`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val newStartTime = startTime.plusSeconds(1800)
      val endTime = startTime.plusSeconds(3600)

      val workshop =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              startTime = startTime,
              endTime = endTime,
              revision = 1,
          )
      val original = eventsDao.findById(workshop)!!

      store.updateEvent(workshop) { it.copy(startTime = newStartTime) }
      val updated = eventsDao.findById(workshop)
      assertEquals(
          original.copy(
              startTime = newStartTime,
              endTime = endTime,
              revision = 2,
              modifiedTime = clock.instant,
          ),
          updated,
      )

      eventPublisher.assertEventPublished(ModuleEventScheduledEvent(workshop, 2))
    }

    @Test
    fun `increments revision and publishes event if end time updated`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val endTime = startTime.plusSeconds(3600)
      val newEndTime = endTime.plusSeconds(1800)
      val workshop =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              startTime = startTime,
              endTime = endTime,
              revision = 1,
          )
      val original = eventsDao.findById(workshop)!!

      store.updateEvent(workshop) { it.copy(endTime = newEndTime) }
      val updated = eventsDao.findById(workshop)
      assertEquals(
          original.copy(
              startTime = startTime,
              endTime = newEndTime,
              revision = 2,
              modifiedTime = clock.instant,
          ),
          updated,
      )

      eventPublisher.assertEventPublished(ModuleEventScheduledEvent(workshop, 2))
    }

    @Test
    fun `sets event status based on time`() {
      clock.instant = Instant.EPOCH.plusSeconds(3600)
      val threeSecondsAgo = clock.instant.minusSeconds(3)
      val twoSecondsAgo = clock.instant.minusSeconds(2)
      val now = clock.instant
      val twoSecondsLater = clock.instant.plusSeconds(2)
      val threeSecondsLater = clock.instant.plusSeconds(3)
      val leadDurationLater = clock.instant.plus(MODULE_EVENT_NOTIFICATION_LEAD_TIME)
      val leadDurationAndTwoSecondsLater = leadDurationLater.plusSeconds(2)
      val leadDurationAndThreeSecondsLater = leadDurationLater.plusSeconds(3)

      val endedEvent = insertEvent(moduleId = moduleId)
      val endingEvent = insertEvent(moduleId = moduleId)
      val inProgressEvent = insertEvent(moduleId = moduleId)
      val startingEvent = insertEvent(moduleId = moduleId)
      val startingInSecondsEvent = insertEvent(moduleId = moduleId)
      val startingSoonEvent = insertEvent(moduleId = moduleId)
      val futureEvent = insertEvent(moduleId = moduleId)

      store.updateEvent(endedEvent) {
        it.copy(startTime = threeSecondsAgo, endTime = twoSecondsAgo)
      }
      store.updateEvent(endingEvent) { it.copy(startTime = twoSecondsAgo, endTime = now) }
      store.updateEvent(inProgressEvent) {
        it.copy(startTime = twoSecondsAgo, endTime = twoSecondsLater)
      }
      store.updateEvent(startingEvent) { it.copy(startTime = now, endTime = twoSecondsLater) }
      store.updateEvent(startingInSecondsEvent) {
        it.copy(startTime = twoSecondsLater, endTime = threeSecondsLater)
      }
      store.updateEvent(startingSoonEvent) {
        it.copy(startTime = leadDurationLater, endTime = leadDurationAndTwoSecondsLater)
      }
      store.updateEvent(futureEvent) {
        it.copy(
            startTime = leadDurationAndTwoSecondsLater,
            endTime = leadDurationAndThreeSecondsLater,
        )
      }

      val results = eventsDao.findAll()
      val statuses = results.associate { it.id to it.eventStatusId }

      assertEquals(statuses[endedEvent], EventStatus.Ended, "end < now events has Ended status")
      assertEquals(statuses[endingEvent], EventStatus.Ended, "end = now events has Ended status")
      assertEquals(
          statuses[inProgressEvent],
          EventStatus.InProgress,
          "start < now < end events has InProgress status",
      )
      assertEquals(
          statuses[startingEvent],
          EventStatus.InProgress,
          "now = start has InProgress status",
      )
      assertEquals(
          statuses[startingInSecondsEvent],
          EventStatus.StartingSoon,
          "start < now < notify events has StartingSoon status",
      )
      assertEquals(
          statuses[startingSoonEvent],
          EventStatus.StartingSoon,
          "now = notify events has StartingSoon status",
      )
      assertEquals(
          statuses[futureEvent],
          EventStatus.NotStarted,
          "notify < now events has NotStarted status",
      )
    }
  }

  @Nested
  inner class UpdateEventStatus {
    @Test
    fun `updates the event status`() {
      every { user.canManageModuleEventStatuses() } returns true

      val eventId = insertEvent(eventStatus = EventStatus.NotStarted)

      assertEquals(
          EventStatus.NotStarted,
          eventsDao.fetchOneById(eventId)!!.eventStatusId,
          "Status before update. ",
      )

      store.updateEventStatus(eventId, EventStatus.Ended)

      assertEquals(
          EventStatus.Ended,
          eventsDao.fetchOneById(eventId)!!.eventStatusId,
          "Status after update. ",
      )
    }

    @Test
    fun `throws exception when event does not exist`() {
      every { user.canManageModuleEventStatuses() } returns true

      assertThrows<EventNotFoundException> {
        store.updateEventStatus(EventId(-1), EventStatus.Ended)
      }
    }

    @Test
    fun `throws exception when no permission to manage module event statuses`() {
      every { user.canManageModuleEventStatuses() } returns false

      val eventId = insertEvent(eventStatus = EventStatus.NotStarted)
      assertThrows<AccessDeniedException> { store.updateEventStatus(eventId, EventStatus.Ended) }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `throws exception if event does not exist`() {
      assertThrows<EventNotFoundException> { store.delete(EventId(-1)) }
    }

    @Test
    fun `throws exception if no permission to manage events`() {
      every { user.canManageModuleEvents() } returns false

      val eventId = insertEvent()
      assertThrows<AccessDeniedException> { store.delete(eventId) }
    }

    @Test
    fun `deletes the event`() {
      val event1 = insertEvent()
      val event2 = insertEvent()

      assertNotEquals(emptyList<EventsRow>(), eventsDao.findAll(), "Events before deletion")
      store.delete(event1)
      assertEquals(
          event2,
          eventsDao.findAll().firstOrNull()?.id,
          "Events contain $event2 after one deletion",
      )
      store.delete(event2)
      assertTableEmpty(EVENTS, "Events after all deletions")
    }
  }
}
