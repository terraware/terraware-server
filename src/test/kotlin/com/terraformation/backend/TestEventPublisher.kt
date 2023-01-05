package com.terraformation.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.context.ApplicationEventPublisher

class TestEventPublisher : ApplicationEventPublisher {
  private val publishedEvents = mutableListOf<Any>()

  override fun publishEvent(event: Any) {
    publishedEvents.add(event)
  }

  /**
   * Asserts that a list of events were published in a specific order. Ignores any additional events
   * not on the list.
   */
  fun assertEventsPublished(events: List<Any>) {
    val expectedEvents = events.toSet()
    val publishedEventsFromExpectedList = publishedEvents.filter { it in expectedEvents }

    assertEquals(events, publishedEventsFromExpectedList, "Expected events not published")
  }

  /**
   * Asserts that a set of events were published in any order. Ignores any additional events not in
   * the set.
   */
  fun assertEventsPublished(events: Set<Any>) {
    val publishedEventsFromExpectedSet = publishedEvents.filter { it in events }.toSet()

    assertEquals(events, publishedEventsFromExpectedSet, "Expected events not published")
  }

  /**
   * Asserts that a list of events, and only that list of events, were published in a specific
   * order.
   */
  fun assertExactEventsPublished(events: List<Any>) {
    assertEquals(events, publishedEvents, "Expected events not published")
  }

  /** Asserts that a set of events, and only that set of events, were published in any order. */
  fun assertExactEventsPublished(events: Set<Any>) {
    assertEquals(events, publishedEvents.toSet(), "Expected events not published")
  }

  /** Asserts that a particular event was published. */
  fun assertEventPublished(event: Any) {
    if (event !in publishedEvents) {
      // Fail with an assertion that shows which events were actually published.
      assertEquals(listOf(event), publishedEvents, "Expected event not published")
    }
  }

  /** Asserts that an event matching a predicate was published. */
  fun assertEventPublished(predicate: (Any) -> Boolean) {
    if (!publishedEvents.any(predicate)) {
      // Fail with an assertion that shows which events were actually published.
      assertEquals(
          "Event matching predicate", publishedEvents as Any, "Expected event not published")
    }
  }

  /** Asserts that no event of a particular type has been published. */
  fun assertEventNotPublished(clazz: Class<*>) {
    assertEquals(
        emptyList<Any>(),
        publishedEvents.filter { clazz.isInstance(it) },
        "Expected no events of type ${clazz.name}")
  }

  /** Asserts that no event of a particular type has been published. */
  inline fun <reified T : Any> assertEventNotPublished() {
    assertEventNotPublished(T::class.java)
  }
}
