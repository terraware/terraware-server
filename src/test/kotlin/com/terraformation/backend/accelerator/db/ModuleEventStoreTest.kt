package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EventNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
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
    insertUser()
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
    fun `creates event`() {
      clock.instant = Instant.EPOCH.plusSeconds(500)
      val startTime = clock.instant.plusSeconds(3600)

      val model = store.create(moduleId, EventType.Workshop, startTime)

      assertEquals(
          listOf(
              EventsRow(
                  id = model.id,
                  moduleId = moduleId,
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
    fun `updates event ignores event id or revision updates`() {
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

      store.updateEvent(workshop) { it.copy(id = EventId(100), revision = 10) }

      val updated = eventsDao.findById(workshop)
      assertEquals(original!!.copy(modifiedTime = clock.instant), updated)
    }

    @Test
    fun `increments revision and publishes event if start time updated`() {
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
      val newRevision = original!!.revision!! + 1
      val newStartTime = startTime.plusSeconds(1800)

      store.updateEvent(workshop) { it.copy(startTime = newStartTime, revision = 10) }

      val updated = eventsDao.findById(workshop)
      assertEquals(
          original.copy(
              startTime = newStartTime, revision = newRevision, modifiedTime = clock.instant),
          updated)

      eventPublisher.assertEventPublished(ModuleEventScheduledEvent(workshop, newRevision))
    }
  }
}
