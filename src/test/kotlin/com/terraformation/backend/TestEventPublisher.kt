package com.terraformation.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.context.ApplicationEventPublisher

class TestEventPublisher : ApplicationEventPublisher {
  private val publishedEvents = mutableListOf<Any>()

  override fun publishEvent(event: Any) {
    publishedEvents.add(event)
  }

  /**
   * Clears the record of published events. This can be used in multi-step tests where an early step
   * is supposed to publish an event but a later one isn't.
   */
  fun clear() {
    publishedEvents.clear()
  }

  /**
   * Asserts that a list of events were published in a specific order. Ignores any additional events
   * not on the list.
   */
  fun assertEventsPublished(events: List<Any>, message: String = "Expected events not published") {
    val expectedEvents = events.toSet()
    val publishedEventsFromExpectedList = publishedEvents.filter { it in expectedEvents }

    assertEquals(events, publishedEventsFromExpectedList, message)
  }

  /**
   * Asserts that a set of events were published in any order. Ignores any additional events not in
   * the set.
   */
  fun assertEventsPublished(events: Set<Any>, message: String = "Expected events not published") {
    val publishedEventsFromExpectedSet = publishedEvents.filter { it in events }.toSet()

    assertEquals(events, publishedEventsFromExpectedSet, message)
  }

  /**
   * Asserts that a list of events, and only that list of events, were published in a specific
   * order.
   */
  fun assertExactEventsPublished(
      events: List<Any>,
      message: String = "Expected events not published"
  ) {
    assertEquals(events, publishedEvents, message)
  }

  /** Asserts that a set of events, and only that set of events, were published in any order. */
  fun assertExactEventsPublished(
      events: Set<Any>,
      message: String = "Expected events not published"
  ) {
    assertEquals(events, publishedEvents.toSet(), message)
  }

  /** Asserts that a particular event was published. */
  fun assertEventPublished(event: Any, message: String = "Expected event not published") {
    if (event !in publishedEvents) {
      // Fail with an assertion that shows which events were actually published.
      assertEquals(listOf(event), publishedEvents, message)
    }
  }

  /** Asserts that an event matching a predicate was published. */
  fun assertEventPublished(
      message: String = "Expected event not published",
      predicate: (Any) -> Boolean
  ) {
    if (!publishedEvents.any(predicate)) {
      // Fail with an assertion that shows which events were actually published.
      assertEquals("Event matching predicate", publishedEvents as Any, message)
    }
  }

  /** Asserts that no event of a particular type has been published. */
  fun assertEventNotPublished(
      clazz: Class<*>,
      message: String = "Expected no events of type ${clazz.name}"
  ) {
    assertEquals(emptyList<Any>(), publishedEvents.filter { clazz.isInstance(it) }, message)
  }

  /** Asserts that no events of any type have been published. */
  fun assertNoEventsPublished(message: String = "Expected no events to be published") {
    assertEquals(emptyList<Any>(), publishedEvents, message)
  }

  /** Asserts that no event of a particular type has been published. */
  inline fun <reified T : Any> assertEventNotPublished(
      message: String = "Expected no events of type ${T::class.java.name}"
  ) {
    assertEventNotPublished(T::class.java, message)
  }
}
