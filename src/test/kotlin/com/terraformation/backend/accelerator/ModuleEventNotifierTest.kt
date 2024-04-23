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
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
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

class ModuleEventNotifierTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val eventStore: ModuleEventStore by lazy {
    ModuleEventStore(clock, dslContext, eventPublisher, eventsDao)
  }
  private val scheduler: JobScheduler = mockk()

  private val notifier: ModuleEventNotifier by lazy {
    ModuleEventNotifier(
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
    insertUser()
    insertOrganization()
    insertModule()
    insertCohort()
    insertParticipant(cohortId = inserted.cohortId)

    projectId = insertProject(participantId = inserted.participantId)

    every { scheduler.schedule<ModuleEventNotifier>(any<Instant>(), any()) } returns
        JobId(UUID.randomUUID())
  }

  @Test
  fun `should have event listener for Module Event Scheduled event`() {
    assertIsEventListener<ModuleEventScheduledEvent>(notifier)
  }

  @Test
  fun `schedules notification at given lead time of module event`() {
    clock.instant = Instant.EPOCH.plusSeconds(3600)
    val startTime = clock.instant.plusSeconds(3600)
    val notifyTime = startTime.minus(ModuleEventNotifier.notificationLeadTime)

    val moduleEventId = insertEvent(revision = 1, startTime = startTime)
    val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

    assertTrue(notifyTime.isAfter((clock.instant())))

    notifier.on(notificationEvent)
    verify(exactly = 1) { scheduler.schedule<ModuleEventNotifier>(notifyTime, any()) }
  }

  @Test
  fun `does not schedule notification if module event is starting within the lead time`() {
    clock.instant = Instant.EPOCH.plusSeconds(3600)
    val startTime = clock.instant.plus(ModuleEventNotifier.notificationLeadTime).minusSeconds(1)
    val notifyTime = startTime.minus(ModuleEventNotifier.notificationLeadTime)

    val moduleEventId = insertEvent(revision = 1, startTime = startTime)
    val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

    assertTrue(notifyTime.isBefore((clock.instant())))

    notifier.on(notificationEvent)
    verify(exactly = 0) { scheduler.schedule<ModuleEventNotifier>(notifyTime, any()) }
  }

  @Nested
  inner class NotifyStartingIfModuleEventUpToDate {
    @Test
    fun `publishes starting event if revision is current`() {
      clock.instant = Instant.EPOCH.plusSeconds(3600)
      val startTime = clock.instant.plusSeconds(3600)
      val moduleEventId = insertEvent(revision = 1, startTime = startTime)
      val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

      notifier.notifyStartingIfModuleEventUpToDate(notificationEvent)
      eventPublisher.assertEventPublished(ModuleEventStartingEvent(moduleEventId))
    }

    @Test
    fun `does not publish starting event if revision is outdated`() {
      clock.instant = Instant.EPOCH.plusSeconds(3600)
      val startTime = clock.instant.plusSeconds(3600)
      val moduleEventId = insertEvent(revision = 2, startTime = startTime)
      val notificationEvent = ModuleEventScheduledEvent(moduleEventId, 1)

      notifier.notifyStartingIfModuleEventUpToDate(notificationEvent)
      eventPublisher.assertEventNotPublished<ModuleEventStartingEvent>()
    }

    @Test
    fun `does not publish starting event if event does not exist`() {
      val notificationEvent = ModuleEventScheduledEvent(EventId(-1), 1)

      notifier.notifyStartingIfModuleEventUpToDate(notificationEvent)
      eventPublisher.assertEventNotPublished<ModuleEventStartingEvent>()
    }
  }
}
