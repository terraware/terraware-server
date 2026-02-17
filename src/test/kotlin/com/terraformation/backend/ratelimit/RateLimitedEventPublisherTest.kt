package com.terraformation.backend.ratelimit

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.RATE_LIMITED_EVENTS
import com.terraformation.backend.mockUser
import java.time.Duration
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class RateLimitedEventPublisherTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val rateLimitedEventPublisher: RateLimitedEventPublisherImpl by lazy {
    RateLimitedEventPublisherImpl(
        clock,
        dslContext,
        eventPublisher,
        jacksonObjectMapper(),
        SystemUser(usersDao),
    )
  }

  @Test
  fun `publishes event immediately if there is no record of a recent one with the same key`() {
    val event = TestEvent()
    val otherKeyEvent = TestEvent(UserId(2))

    rateLimitedEventPublisher.publishEvent(otherKeyEvent)
    eventPublisher.clear()

    rateLimitedEventPublisher.publishEvent(event)

    eventPublisher.assertEventPublished(event)
  }

  @Test
  fun `does not publish event immediately if another one has just been published`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishEvent(event)
    eventPublisher.clear()

    rateLimitedEventPublisher.publishEvent(event)

    eventPublisher.assertEventNotPublished<TestEvent>()
  }

  @Test
  fun `publishes event immediately if previous one was longer ago than the minimum interval`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishEvent(event)
    eventPublisher.clear()

    clock.instant += event.getMinimumInterval().plusSeconds(1)

    rateLimitedEventPublisher.publishEvent(event)

    eventPublisher.assertEventPublished(event)
  }

  @Test
  fun `combines pending event with current event`() {
    val event1 = TestEvent(data = 1)
    val event2 = TestEvent(data = 2)
    val event4 = TestEvent(data = 4)
    val combinedEvent2And4 = TestEvent(data = 6)

    rateLimitedEventPublisher.publishEvent(event1)
    eventPublisher.clear()
    rateLimitedEventPublisher.publishEvent(event2)

    clock.instant += event1.getMinimumInterval().minusSeconds(1)
    rateLimitedEventPublisher.publishEvent(event4)

    clock.instant += Duration.ofSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertEventPublished(combinedEvent2And4)
  }

  @Test
  fun `does not publish pending event until the minimum interval has elapsed`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishEvent(event)
    eventPublisher.clear()
    rateLimitedEventPublisher.publishEvent(event)

    clock.instant += event.getMinimumInterval().minusSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertNoEventsPublished("Published event before initial interval")

    clock.instant += Duration.ofSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertEventPublished(event, "Published event after initial interval")
    eventPublisher.clear()

    clock.instant += event.getMinimumInterval().minusSeconds(1)
    rateLimitedEventPublisher.publishEvent(event)

    eventPublisher.assertNoEventsPublished("Published event before second interval")

    clock.instant += Duration.ofSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertEventPublished(event, "Published event after second interval")
  }

  @Test
  fun `cleans up rate limit records once they are no longer relevant`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishEvent(event)
    eventPublisher.clear()

    clock.instant += event.getMinimumInterval()
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertNoEventsPublished()

    assertTableEmpty(RATE_LIMITED_EVENTS)
  }

  @Test
  fun `publishes non-rate-limited events immediately`() {
    val event = "an object that does not implement RateLimitedEvent"

    rateLimitedEventPublisher.publishEvent(event)

    eventPublisher.assertEventPublished(event, "Initial event")
    eventPublisher.clear()

    rateLimitedEventPublisher.publishEvent(event)

    eventPublisher.assertEventPublished(event, "Second event")

    assertTableEmpty(RATE_LIMITED_EVENTS)
  }

  data class TestEvent(
      val userId: UserId = UserId(1),
      val projectId: ProjectId = ProjectId(2),
      val data: Int = 1,
  ) : RateLimitedEvent<TestEvent> {
    override fun getRateLimitKey() = mapOf("userId" to userId, "projectId" to projectId)

    override fun getMinimumInterval(): Duration = Duration.ofMinutes(5)

    override fun combine(existing: TestEvent) = TestEvent(userId, projectId, data + existing.data)
  }
}
