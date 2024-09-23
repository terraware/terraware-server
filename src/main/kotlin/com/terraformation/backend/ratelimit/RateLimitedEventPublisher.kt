package com.terraformation.backend.ratelimit

import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/**
 * Publishes application events. For most events, this can be used in place of
 * [ApplicationEventPublisher]. But for events that implement [RateLimitedEvent], this class defers
 * them if they are published too often.
 *
 * The basic flow looks like:
 * 1. Construct a [RateLimitedEvent] that identifies the target of the rate limit, e.g., a user ID /
 *    project ID pair.
 * 2. Call [publishEvent] with the event.
 * 3. Handle the event in an [EventListener]-annotated listener method.
 *
 * If the event hasn't been published recently with the same rate limit target, [publishEvent]
 * publishes it immediately.
 *
 * Otherwise, the event is stored in the database (possibly after being combined with an existing
 * pending event) and will be published once the minimum interval between events has elapsed.
 */
interface RateLimitedEventPublisher {
  fun <T : RateLimitedEvent<T>> publishEvent(event: T)

  /** Publishes a non-rate-limited event immediately. */
  fun publishEvent(event: Any)
}
