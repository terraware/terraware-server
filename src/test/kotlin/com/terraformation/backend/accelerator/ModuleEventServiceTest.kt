package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.event.ModuleEventStartingEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ModuleEventServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val eventStore: ModuleEventStore by lazy {
    ModuleEventStore(clock, dslContext, eventPublisher, eventsDao)
  }

  private val scheduler: JobScheduler = mockk()

  private val service: ModuleEventService by lazy {
    ModuleEventService(
        clock,
        eventPublisher,
        eventStore,
        scheduler,
        SystemUser(usersDao),
    )
  }

  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertModule()
    insertCohort()

    projectId = insertProject(cohortId = inserted.cohortId)

    every { scheduler.schedule<ModuleEventService>(any<Instant>(), any()) } returns
        JobId(UUID.randomUUID())
  }

  @Test
  fun `should have event listener for Module Event Scheduled event`() {
    assertIsEventListener<ModuleEventScheduledEvent>(service)
  }

  @Test
  fun `schedules jobs at notify, start and end times, if now is before notify time`() {
    clock.instant = Instant.EPOCH.plusSeconds(3600)

    val notifyTime = clock.instant.plusSeconds(1)
    val startTime = notifyTime.plus(MODULE_EVENT_NOTIFICATION_LEAD_TIME)
    val endTime = startTime.plusSeconds(3600)

    val moduleEventId = insertEvent(revision = 1, startTime = startTime, endTime = endTime)
    val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

    service.on(notificationEvent)
    verify(exactly = 2) { scheduler.schedule<ModuleEventService>(notifyTime, any()) }
    verify(exactly = 1) { scheduler.schedule<ModuleEventService>(startTime, any()) }
    verify(exactly = 1) { scheduler.schedule<ModuleEventService>(endTime, any()) }
    confirmVerified(scheduler)
  }

  @Test
  fun `schedules jobs at start and end time, if now is between notify and start time`() {
    clock.instant = Instant.EPOCH.plusSeconds(3600)

    val notifyTime = clock.instant.minusSeconds(1)
    val startTime = notifyTime.plus(MODULE_EVENT_NOTIFICATION_LEAD_TIME)
    val endTime = startTime.plusSeconds(3600)

    val moduleEventId = insertEvent(revision = 1, startTime = startTime, endTime = endTime)
    val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

    service.on(notificationEvent)
    verify(exactly = 1) { scheduler.schedule<ModuleEventService>(startTime, any()) }
    verify(exactly = 1) { scheduler.schedule<ModuleEventService>(endTime, any()) }
    confirmVerified(scheduler)
  }

  @Test
  fun `schedules jobs at end time, if now is between start and end time`() {
    clock.instant = Instant.EPOCH.plusSeconds(3600)
    val startTime = clock.instant.minusSeconds(1)
    val endTime = startTime.plusSeconds(3600)

    val moduleEventId = insertEvent(revision = 1, startTime = startTime, endTime = endTime)
    val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

    service.on(notificationEvent)
    verify(exactly = 1) { scheduler.schedule<ModuleEventService>(endTime, any()) }
    confirmVerified(scheduler)
  }

  @Test
  fun `does not schedule any job if now is after end time`() {
    clock.instant = Instant.EPOCH.plusSeconds(3600)
    val endTime = clock.instant.minusSeconds(1)
    val startTime = endTime.minusSeconds(600)

    val moduleEventId = insertEvent(revision = 1, startTime = startTime, endTime = endTime)
    val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

    service.on(notificationEvent)
    confirmVerified(scheduler)
  }

  @Nested
  inner class NotifyStartingIfModuleEventUpToDate {
    @Test
    fun `publishes starting event if revision is current`() {
      clock.instant = Instant.EPOCH.plusSeconds(3600)
      val startTime = clock.instant.plusSeconds(3600)
      val moduleEventId = insertEvent(revision = 1, startTime = startTime)
      val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

      service.notifyStartingIfModuleEventUpToDate(notificationEvent)
      eventPublisher.assertEventPublished(ModuleEventStartingEvent(moduleEventId))
    }

    @Test
    fun `does not publish starting event if revision is outdated`() {
      clock.instant = Instant.EPOCH.plusSeconds(3600)
      val startTime = clock.instant.plusSeconds(3600)
      val moduleEventId = insertEvent(revision = 2, startTime = startTime)
      val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

      service.notifyStartingIfModuleEventUpToDate(notificationEvent)
      eventPublisher.assertEventNotPublished<ModuleEventStartingEvent>()
    }

    @Test
    fun `does not publish starting event if event does not exist`() {
      val notificationEvent = ModuleEventScheduledEvent(EventId(-1), 1)

      service.notifyStartingIfModuleEventUpToDate(notificationEvent)
      eventPublisher.assertEventNotPublished<ModuleEventStartingEvent>()
    }
  }

  @Nested
  inner class UpdateEventStatusIfEventUpToDate {
    @Test
    fun `updates event status if revision is current`() {
      val moduleEventId = insertEvent(revision = 1, eventStatus = EventStatus.NotStarted)
      val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

      assertEquals(
          EventStatus.NotStarted,
          eventsDao.fetchOneById(moduleEventId)!!.eventStatusId,
          "Status before update.",
      )

      service.updateEventStatusIfEventUpToDate(notificationEvent, EventStatus.Ended)

      assertEquals(
          EventStatus.Ended,
          eventsDao.fetchOneById(moduleEventId)!!.eventStatusId,
          "Status after update.",
      )
    }

    @Test
    fun `does not update event status if revision is outdated`() {
      val moduleEventId = insertEvent(revision = 1, eventStatus = EventStatus.NotStarted)
      val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 2)

      assertEquals(
          EventStatus.NotStarted,
          eventsDao.fetchOneById(moduleEventId)!!.eventStatusId,
          "Status before update.",
      )

      service.updateEventStatusIfEventUpToDate(notificationEvent, EventStatus.Ended)

      assertEquals(
          EventStatus.NotStarted,
          eventsDao.fetchOneById(moduleEventId)!!.eventStatusId,
          "Status after update.",
      )
    }

    @Test
    fun `does not throw exception if event does not exist`() {
      val notificationEvent = ModuleEventScheduledEvent(EventId(-1), 1)

      assertDoesNotThrow {
        service.updateEventStatusIfEventUpToDate(notificationEvent, EventStatus.Ended)
      }
    }
  }
}
