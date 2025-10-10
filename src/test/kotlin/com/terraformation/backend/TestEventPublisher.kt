package com.terraformation.backend

import com.terraformation.backend.ratelimit.RateLimitedEvent
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.springframework.context.ApplicationEventPublisher

/**
 * Test double for event publication. This doesn't call any event listeners, just collects the
 * published events and allows tests to assert things about them.
 *
 * This class implements both [ApplicationEventPublisher] (the Spring interface) and
 * [RateLimitedEventPublisher], and can be used as a double for either. If your calling class
 * publishes both regular and rate-limited events, and depends on both [ApplicationEventPublisher]
 * and [RateLimitedEventPublisher], you will probably want two instances of this class so you can
 * assert that your events were published with (or without) rate limiting.
 */
class TestEventPublisher : ApplicationEventPublisher, RateLimitedEventPublisher {
  private val eventListeners = mutableListOf<Pair<Class<*>, (Any) -> Unit>>()
  private val publishedEvents = mutableListOf<Any>()

  override fun publishEvent(event: Any) {
    callListeners(event)
    publishedEvents.add(event)
  }

  override fun <T : RateLimitedEvent<T>> publishEvent(event: T) {
    callListeners(event)
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
      message: String = "Expected events not published",
  ) {
    assertEquals(events, publishedEvents, message)
  }

  /** Asserts that a set of events, and only that set of events, were published in any order. */
  fun assertExactEventsPublished(
      events: Set<Any>,
      message: String = "Expected events not published",
  ) {
    assertSetEquals(events, publishedEvents.toSet(), message)
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
      predicate: (Any) -> Boolean,
  ) {
    if (!publishedEvents.any(predicate)) {
      // Fail with an assertion that shows which events were actually published.
      assertEquals("Event matching predicate", publishedEvents as Any, message)
    }
  }

  /** Asserts that a particular event has not been published. */
  fun assertEventNotPublished(event: Any, message: String = "Expected event not to be published") {
    if (event in publishedEvents) {
      // Fail with an assertion that shows which events were actually published.
      assertNotEquals(listOf(event), publishedEvents, message)
    }
  }

  /** Asserts that no event of a particular type has been published. */
  fun assertEventNotPublished(
      clazz: Class<*>,
      message: String = "Expected no events of type ${clazz.name}",
  ) {
    assertEquals(emptyList<Any>(), publishedEvents.filter { clazz.isInstance(it) }, message)
  }

  /** Asserts that no events of any type have been published. */
  fun assertNoEventsPublished(message: String = "Expected no events to be published") {
    assertEquals(emptyList<Any>(), publishedEvents, message)
  }

  /** Registers a listener function for events of a given class. */
  fun <T : Any> register(clazz: Class<T>, listener: (T) -> Unit) {
    @Suppress("UNCHECKED_CAST") eventListeners.add(clazz to listener as (Any) -> Unit)
  }

  /** Registers a listener function for events of a given class. */
  inline fun <reified T : Any> register(noinline listener: (T) -> Unit) {
    register(T::class.java, listener)
  }

  /** Asserts that no event of a particular type has been published. */
  inline fun <reified T : Any> assertEventNotPublished(
      message: String = "Expected no events of type ${T::class.java.name}"
  ) {
    assertEventNotPublished(T::class.java, message)
  }

  private fun callListeners(event: Any) {
    eventListeners.forEach { (clazz, listener) ->
      if (clazz.isInstance(event)) {
        listener(event)
      }
    }
  }
}
