package com.terraformation.backend.ratelimit

import org.springframework.context.event.EventListener

/**
 * Limits the rate of events, deferring them if they are published too often.
 *
 * The basic flow looks like:
 * 1. Construct a [RateLimitedEvent] that identifies the target of the rate limit, e.g., a user ID /
 *    project ID pair.
 * 2. Call [publishOrDefer] with the event.
 * 3. Handle the event in an [EventListener]-annotated listener method.
 *
 * If the event hasn't been published recently with the same rate limit target, [publishOrDefer]
 * publishes it immediately.
 *
 * Otherwise, the event is stored in the database (possibly after being combined with an existing
 * pending event) and will be published once the minimum interval between events has elapsed.
 */
interface RateLimitedEventPublisher {
  fun <T : RateLimitedEvent<T>> publishOrDefer(event: T)
}
