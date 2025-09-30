package com.terraformation.backend.ratelimit

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Duration

/**
 * Implemented by events that can be subject to rate limits. See [RateLimitedEventPublisher] for
 * details about how this is used.
 *
 * Rate-limited events are serialized to JSON and stored in the database, so shouldn't include any
 * non-serializable values.
 *
 * # Changing event classes
 *
 * If you make changes to a rate-limited event class, keep in mind that there may be existing
 * instances of the previous version of the class sitting in the database at the time your change is
 * deployed. You will need to do one of the following:
 * 1. Make sure your change is backward-compatible. For example, adding a new nullable property is
 *    fine since objects without the property will still be valid.
 * 2. Write a database migration to convert the old version to the new version. For example, if you
 *    are renaming an event class or moving it to a different package, but not changing its
 *    contents, you can write a migration to update the class names in the `rate_limited_events`
 *    table.
 * 3. Make a new event and keep the old one around for one release. Once any existing pending events
 *    have been published, you can remove the old event.
 */
interface RateLimitedEvent<T : RateLimitedEvent<T>> {
  /**
   * Returns an object that uniquely identifies the rate-limiting target of this event. For example,
   * if the action triggered by this event should only be performed every N minutes for a given
   * user, this would be the user ID. If the action should only be performed every N minutes for a
   * given user in a given project, but is rate limited separately for the same user across
   * different projects, this would be an object containing both the user ID and the project ID.
   *
   * The returned key will be serialized to JSON, so it shouldn't include any non-serializable
   * values.
   */
  @JsonIgnore fun getRateLimitKey(): Any

  /** Returns the minimum amount of time between events with the same rate limit key. */
  @JsonIgnore fun getMinimumInterval(): Duration

  /**
   * Combines an existing pending event with this one. This can be used to, for example, generate
   * notifications like "there have been 3 edits to such-and-such entity." The existing pending
   * event is replaced with the return value of this function, but continues to be scheduled for the
   * same time.
   *
   * If this returns [existing] (the default behavior), the effect is to discard additional events
   * during the rate-limit period.
   *
   * If this returns `this`, the effect is to only keep the most recent event during the rate-limit
   * period.
   */
  fun combine(existing: T): T = existing
}
