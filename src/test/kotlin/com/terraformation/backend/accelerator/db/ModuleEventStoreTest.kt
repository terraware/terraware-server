package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.MODULE_EVENT_NOTIFICATION_LEAD_TIME
import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EventNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.pojos.EventProjectsRow
import com.terraformation.backend.db.accelerator.tables.pojos.EventsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
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
  private lateinit var cohortId: CohortId
  private lateinit var moduleId: ModuleId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    cohortId = insertCohort()
    insertParticipant(cohortId = cohortId)
    moduleId = insertModule()
    insertCohortModule(cohortId, moduleId)

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
    fun `throws exception no permission to view event or participants`() {
      every { user.canReadModuleEventParticipants() } returns false
      insertProject(participantId = inserted.participantId)
      val eventId = insertEvent()
      assertThrows<AccessDeniedException> { store.fetchOneById(eventId) }
    }

    @Test
    fun `returns event with participants`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val endTime = startTime.plusSeconds(3600)
      val project1 = insertProject(participantId = inserted.participantId)
      val project2 = insertProject(participantId = inserted.participantId)

      val workshop =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              meetingUrl = "https://meeting.com",
              slidesUrl = "https://slides.com",
              recordingUrl = "https://recording.com",
              startTime = startTime,
              endTime = endTime)

      assertEquals(
          EventModel(
              id = workshop,
              eventStatus = EventStatus.NotStarted,
              eventType = EventType.Workshop,
              moduleId = moduleId,
              meetingUrl = URI("https://meeting.com"),
              slidesUrl = URI("https://slides.com"),
              recordingUrl = URI("https://recording.com"),
              revision = 1,
              startTime = startTime,
              endTime = endTime,
              projects = emptySet()),
          store.fetchOneById(workshop))

      insertEventProject(workshop, project1)
      insertEventProject(workshop, project2)

      assertEquals(
          EventModel(
              id = workshop,
              eventStatus = EventStatus.NotStarted,
              eventType = EventType.Workshop,
              moduleId = moduleId,
              meetingUrl = URI("https://meeting.com"),
              slidesUrl = URI("https://slides.com"),
              recordingUrl = URI("https://recording.com"),
              revision = 1,
              startTime = startTime,
              endTime = endTime,
              projects = setOf(project1, project2)),
          store.fetchOneById(workshop))
    }
  }

  @Nested
  inner class FetchOneForProjectById {
    @Test
    fun `throws exception if event exists but project is not a participant`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val eventId = insertEvent()
      assertThrows<EventNotFoundException> { store.fetchOneForProjectById(eventId, projectId) }
    }

    @Test
    fun `returns event without participants`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val endTime = startTime.plusSeconds(3600)
      val project1 = insertProject(participantId = inserted.participantId)
      val project2 = insertProject(participantId = inserted.participantId)

      val workshop =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              meetingUrl = "https://meeting.com",
              slidesUrl = "https://slides.com",
              recordingUrl = "https://recording.com",
              startTime = startTime,
              endTime = endTime)

      insertEventProject(workshop, project1)
      insertEventProject(workshop, project2)

      assertEquals(
          EventModel(
              id = workshop,
              eventStatus = EventStatus.NotStarted,
              eventType = EventType.Workshop,
              moduleId = moduleId,
              meetingUrl = URI("https://meeting.com"),
              slidesUrl = URI("https://slides.com"),
              recordingUrl = URI("https://recording.com"),
              revision = 1,
              startTime = startTime,
              endTime = endTime,
          ),
          store.fetchOneForProjectById(workshop, project1))
    }

    @Test
    fun `throws exception no permission to view event or participants`() {
      every { user.canReadModuleEvent(any()) } returns false
      val projectId = insertProject(participantId = inserted.participantId)
      val eventId = insertEvent()
      insertEventProject(eventId, projectId)
      assertThrows<EventNotFoundException> { store.fetchOneForProjectById(eventId, projectId) }

      every { user.canReadModuleEvent(any()) } returns true
      every { user.canReadProject(any()) } returns false
      assertThrows<ProjectNotFoundException> { store.fetchOneForProjectById(eventId, projectId) }

      every { user.canReadProject(any()) } returns true
      every { user.canReadModuleEvent(any()) } returns true
      assertDoesNotThrow { store.fetchOneForProjectById(eventId, projectId) }
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
              )),
          eventsDao.findAll())

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
              moduleId, EventType.Workshop, startTime = threeSecondsAgo, endTime = twoSecondsAgo)
      val endingEvent =
          store.create(moduleId, EventType.Workshop, startTime = twoSecondsAgo, endTime = now)
      val inProgressEvent =
          store.create(
              moduleId, EventType.Workshop, startTime = twoSecondsAgo, endTime = twoSecondsLater)
      val startingEvent =
          store.create(moduleId, EventType.Workshop, startTime = now, endTime = twoSecondsLater)
      val startingInSecondsEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = twoSecondsLater,
              endTime = threeSecondsLater)
      val startingSoonEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = leadDurationLater,
              endTime = leadDurationAndTwoSecondsLater)
      val futureEvent =
          store.create(
              moduleId,
              EventType.Workshop,
              startTime = leadDurationAndTwoSecondsLater,
              endTime = leadDurationAndThreeSecondsLater)

      val results = eventsDao.findAll()
      val statuses = results.associate { it.id to it.eventStatusId }

      assertEquals(statuses[endedEvent.id], EventStatus.Ended, "end < now events has Ended status")
      assertEquals(statuses[endingEvent.id], EventStatus.Ended, "end = now events has Ended status")
      assertEquals(
          statuses[inProgressEvent.id],
          EventStatus.InProgress,
          "start < now < end events has InProgress status")
      assertEquals(
          statuses[startingEvent.id],
          EventStatus.InProgress,
          "now = start events has InProgress status")
      assertEquals(
          statuses[startingInSecondsEvent.id],
          EventStatus.StartingSoon,
          "start < now < notify events has StartingSoon status")
      assertEquals(
          statuses[startingSoonEvent.id],
          EventStatus.StartingSoon,
          "now = notify events has StartingSoon status")
      assertEquals(
          statuses[futureEvent.id],
          EventStatus.NotStarted,
          "notify < now events has NotStarted status")
    }

    @Test
    fun `creates event project rows when projects are provided`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)
      val project1 = insertProject(participantId = inserted.participantId)
      val project2 = insertProject(participantId = inserted.participantId)
      val model =
          store.create(
              moduleId, EventType.Workshop, startTime, projects = setOf(project1, project2))

      assertNotNull(eventsDao.fetchOneById(model.id))

      assertEquals(
          setOf(
              EventProjectsRow(model.id, project1),
              EventProjectsRow(model.id, project2),
          ),
          eventProjectsDao.findAll().toSet())
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
              endTime = endTime)

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
          eventsDao.findById(workshop))

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
          eventsDao.findById(workshop))

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
              endTime = endTime)

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
              revision = 1)
      val original = eventsDao.findById(workshop)!!

      store.updateEvent(workshop) { it.copy(startTime = newStartTime) }
      val updated = eventsDao.findById(workshop)
      assertEquals(
          original.copy(
              startTime = newStartTime,
              endTime = endTime,
              revision = 2,
              modifiedTime = clock.instant),
          updated)

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
              revision = 1)
      val original = eventsDao.findById(workshop)!!

      store.updateEvent(workshop) { it.copy(endTime = newEndTime) }
      val updated = eventsDao.findById(workshop)
      assertEquals(
          original.copy(
              startTime = startTime,
              endTime = newEndTime,
              revision = 2,
              modifiedTime = clock.instant),
          updated)

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
            startTime = leadDurationAndTwoSecondsLater, endTime = leadDurationAndThreeSecondsLater)
      }

      val results = eventsDao.findAll()
      val statuses = results.associate { it.id to it.eventStatusId }

      assertEquals(statuses[endedEvent], EventStatus.Ended, "end < now events has Ended status")
      assertEquals(statuses[endingEvent], EventStatus.Ended, "end = now events has Ended status")
      assertEquals(
          statuses[inProgressEvent],
          EventStatus.InProgress,
          "start < now < end events has InProgress status")
      assertEquals(
          statuses[startingEvent], EventStatus.InProgress, "now = start has InProgress status")
      assertEquals(
          statuses[startingInSecondsEvent],
          EventStatus.StartingSoon,
          "start < now < notify events has StartingSoon status")
      assertEquals(
          statuses[startingSoonEvent],
          EventStatus.StartingSoon,
          "now = notify events has StartingSoon status")
      assertEquals(
          statuses[futureEvent],
          EventStatus.NotStarted,
          "notify < now events has NotStarted status")
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
          "Status before update. ")

      store.updateEventStatus(eventId, EventStatus.Ended)

      assertEquals(
          EventStatus.Ended,
          eventsDao.fetchOneById(eventId)!!.eventStatusId,
          "Status after update. ")
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

      assertNotEquals(
          emptyList<EventsRow>(),
          eventsDao.findAll().isNotEmpty(),
          "Events before deletion",
      )
      store.delete(event1)
      assertEquals(
          event2,
          eventsDao.findAll().firstOrNull()?.id,
          "Events contain $event2 after one deletion",
      )
      store.delete(event2)
      assertEquals(
          emptyList<EventsRow>(),
          eventsDao.findAll(),
          "Events after all deletions",
      )
    }
  }
}
