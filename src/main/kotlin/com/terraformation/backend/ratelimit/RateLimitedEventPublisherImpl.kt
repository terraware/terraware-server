package com.terraformation.backend.ratelimit

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.tables.references.RATE_LIMITED_EVENTS
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

/**
 * Limits the rate of events, deferring them if they are published too often.
 *
 * Users of this service should generally use the interface [RateLimitedEventPublisher] instead of
 * depending directly on this implementation, so that an alternate implementation can be used in
 * unit tests.
 *
 * @see RateLimitedEventPublisher
 */
@Named
class RateLimitedEventPublisherImpl(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
    private val systemUser: SystemUser,
) : RateLimitedEventPublisher {
  private val log = perClassLogger()

  override fun publishEvent(event: Any) {
    eventPublisher.publishEvent(event)
  }

  override fun <T : RateLimitedEvent<T>> publishEvent(event: T) {
    publishOrDefer(event, true)
  }

  private fun <T : RateLimitedEvent<T>> publishOrDefer(event: T, canRetry: Boolean) {
    val now = clock.instant()
    val eventClass = event.javaClass.name
    val rateLimitKey = toJsonb(event.getRateLimitKey())
    var canPublishEventNow = false
    var eventRecordVanished = false

    // First try the optimistic approach: the event hasn't been published recently so there is
    // no rate limiting record for it.
    val rowsInserted =
        with(RATE_LIMITED_EVENTS) {
          dslContext
              .insertInto(RATE_LIMITED_EVENTS)
              .set(EVENT_CLASS, eventClass)
              .set(RATE_LIMIT_KEY, rateLimitKey)
              .set(NEXT_TIME, now + event.getMinimumInterval())
              .onConflictDoNothing()
              .execute()
        }

    if (rowsInserted == 1) {
      canPublishEventNow = true
    } else {
      dslContext.transaction { _ ->
        with(RATE_LIMITED_EVENTS) {
          val existingRecord =
              dslContext
                  .select(NEXT_TIME.asNonNullable(), PENDING_EVENT)
                  .from(RATE_LIMITED_EVENTS)
                  .where(EVENT_CLASS.eq(eventClass))
                  .and(RATE_LIMIT_KEY.eq(rateLimitKey))
                  .forUpdate()
                  .fetchOne()

          if (existingRecord != null) {
            val (nextTime, pendingEventJsonb) = existingRecord
            if (nextTime < now) {
              // Minimum interval has passed but we haven't deleted the rate limit record yet;
              // it's safe to publish this event immediately.
              dslContext
                  .update(RATE_LIMITED_EVENTS)
                  .set(NEXT_TIME, now + event.getMinimumInterval())
                  .where(EVENT_CLASS.eq(eventClass))
                  .and(RATE_LIMIT_KEY.eq(rateLimitKey))
                  .execute()

              canPublishEventNow = true
            } else {
              // If there is already an event waiting to be published when the interval expires,
              // combine it with this event if needed.
              val existingEvent =
                  pendingEventJsonb?.let { objectMapper.readValue(it.data(), event.javaClass) }
              val combinedEvent = if (existingEvent != null) event.combine(existingEvent) else event

              if (combinedEvent != existingEvent) {
                dslContext
                    .update(RATE_LIMITED_EVENTS)
                    .set(PENDING_EVENT, toJsonb(combinedEvent))
                    .where(EVENT_CLASS.eq(eventClass))
                    .and(RATE_LIMIT_KEY.eq(rateLimitKey))
                    .execute()
              }
            }
          } else {
            eventRecordVanished = true
          }
        }
      }
    }

    if (canPublishEventNow) {
      systemUser.run { eventPublisher.publishEvent(event) }
    } else if (eventRecordVanished) {
      if (canRetry) {
        publishOrDefer(event, false)
      } else {
        log.error("Rate limiting record for $eventClass $rateLimitKey vanished twice in a row")
        log.info("Unable to defer event: $event")
        throw IllegalStateException("Unable to defer event")
      }
    }
  }

  @Job(name = SCAN_PENDING_EVENTS_JOB_NAME, retries = 0)
  @Recurring(id = SCAN_PENDING_EVENTS_JOB_NAME, cron = "* * * * *")
  fun scanPendingEvents() {
    val now = clock.instant()

    val eventsToPublish =
        dslContext.transactionResult { _ ->
          with(RATE_LIMITED_EVENTS) {
            val recordsPastInterval =
                dslContext
                    .selectFrom(RATE_LIMITED_EVENTS)
                    .where(NEXT_TIME.le(now))
                    .forUpdate()
                    .skipLocked()
                    .fetch()

            recordsPastInterval.mapNotNull { record ->
              if (record.pendingEvent != null) {
                try {
                  val event =
                      objectMapper.readValue(
                          record.pendingEvent!!.data(),
                          Class.forName(record.eventClass),
                      ) as RateLimitedEvent<*>

                  // Reset the timer so that any subsequent events within the interval will get
                  // deferred.
                  dslContext
                      .update(RATE_LIMITED_EVENTS)
                      .set(NEXT_TIME, now + event.getMinimumInterval())
                      .set(PENDING_EVENT, DSL.castNull(PENDING_EVENT))
                      .where(EVENT_CLASS.eq(record.eventClass))
                      .and(RATE_LIMIT_KEY.eq(record.rateLimitKey))
                      .execute()

                  event
                } catch (e: ClassNotFoundException) {
                  log.error("Pending event class ${record.eventClass} no longer exists")
                  null
                } catch (e: JsonMappingException) {
                  log.error("Cannot deserialize pending event of type ${record.eventClass}")
                  log.info("JSON that failed to deserialize: ${record.pendingEvent?.data()}")
                  null
                } catch (e: Exception) {
                  log.error("Error processing pending event of type ${record.eventClass}")
                  log.info("JSON of event that failed to process: ${record.pendingEvent?.data()}")
                  null
                }
              } else {
                // We're past the minimum interval since the last event of this class/key and no
                // event has been deferred in the meantime; delete the rate limiting record since it
                // no longer matters.
                log.debug(
                    "Deleting rate limit record for event ${record.eventClass} ${record.rateLimitKey}"
                )
                dslContext
                    .deleteFrom(RATE_LIMITED_EVENTS)
                    .where(EVENT_CLASS.eq(record.eventClass))
                    .and(RATE_LIMIT_KEY.eq(record.rateLimitKey))
                    .execute()

                null
              }
            }
          }
        }

    eventsToPublish.forEach { event ->
      try {
        systemUser.run { eventPublisher.publishEvent(event) }
      } catch (e: Exception) {
        log.error("Error publishing pending event $event", e)
      }
    }
  }

  private fun toJsonb(obj: Any): JSONB {
    return JSONB.valueOf(objectMapper.writeValueAsString(obj))
  }

  companion object {
    private const val SCAN_PENDING_EVENTS_JOB_NAME = "RateLimitedEventPublisher.scanPendingEvents"
  }
}
