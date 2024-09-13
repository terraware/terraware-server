package com.terraformation.backend.ratelimit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.RATE_LIMITED_EVENTS
import com.terraformation.backend.mockUser
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
    )
  }

  @Test
  fun `publishes event immediately if there is no record of a recent one with the same key`() {
    val event = TestEvent()
    val otherKeyEvent = TestEvent(UserId(2))

    rateLimitedEventPublisher.publishOrDefer(otherKeyEvent)
    eventPublisher.clear()

    rateLimitedEventPublisher.publishOrDefer(event)

    eventPublisher.assertEventPublished(event)
  }

  @Test
  fun `does not publish event immediately if another one has just been published`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishOrDefer(event)
    eventPublisher.clear()

    rateLimitedEventPublisher.publishOrDefer(event)

    eventPublisher.assertEventNotPublished<TestEvent>()
  }

  @Test
  fun `publishes event immediately if previous one was longer ago than the minimum interval`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishOrDefer(event)
    eventPublisher.clear()

    clock.instant += event.getMinimumInterval().plusSeconds(1)

    rateLimitedEventPublisher.publishOrDefer(event)

    eventPublisher.assertEventPublished(event)
  }

  @Test
  fun `combines pending event with current event`() {
    val event1 = TestEvent(data = 1)
    val event2 = TestEvent(data = 2)
    val event4 = TestEvent(data = 4)
    val combinedEvent2And4 = TestEvent(data = 6)

    rateLimitedEventPublisher.publishOrDefer(event1)
    eventPublisher.clear()
    rateLimitedEventPublisher.publishOrDefer(event2)

    clock.instant += event1.getMinimumInterval().minusSeconds(1)
    rateLimitedEventPublisher.publishOrDefer(event4)

    clock.instant += Duration.ofSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertEventPublished(combinedEvent2And4)
  }

  @Test
  fun `does not publish pending event until the minimum interval has elapsed`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishOrDefer(event)
    eventPublisher.clear()
    rateLimitedEventPublisher.publishOrDefer(event)

    clock.instant += event.getMinimumInterval().minusSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertNoEventsPublished("Published event before initial interval")

    clock.instant += Duration.ofSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertEventPublished(event, "Published event after initial interval")
    eventPublisher.clear()

    clock.instant += event.getMinimumInterval().minusSeconds(1)
    rateLimitedEventPublisher.publishOrDefer(event)

    eventPublisher.assertNoEventsPublished("Published event before second interval")

    clock.instant += Duration.ofSeconds(1)
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertEventPublished(event, "Published event after second interval")
  }

  @Test
  fun `cleans up rate limit records once they are no longer relevant`() {
    val event = TestEvent()

    rateLimitedEventPublisher.publishOrDefer(event)
    eventPublisher.clear()

    clock.instant += event.getMinimumInterval()
    rateLimitedEventPublisher.scanPendingEvents()

    eventPublisher.assertNoEventsPublished()

    assertEquals(
        emptyList<Any>(), dslContext.selectFrom(RATE_LIMITED_EVENTS).fetch(), "Rate limit records")
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
