package com.terraformation.backend.time

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.tables.references.TEST_CLOCK
import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.PreDestroy
import jakarta.inject.Inject
import jakarta.inject.Named
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Timer
import kotlin.concurrent.timer
import org.jooq.DSLContext
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order

/**
 * User-adjustable clock with database-backed settings.
 *
 * Most application code shouldn't need to be aware of this class; it should declare [Clock]
 * dependencies and call the normal [Clock] APIs.
 *
 * In production environments, this is a facade around the system clock and returns the current
 * time.
 *
 * In user testing environments, however, users need to be able to test business logic that gets
 * triggered by the passage of time. This class allows users to manually advance the clock such that
 * the fake time persists across server restarts. The clock still advances in real time on its own.
 *
 * It works by keeping two timestamps in the database: a base real time and a base fake time. The
 * difference between the base real time and the current system time is added to the base fake time.
 * So if you add a day to the base fake time, the clock will appear to jump forward by a day.
 *
 * This setup is intended to work well with database snapshots; a desired set of test data can be
 * created, including scheduled dates, and the database snapshot will include the clock
 * configuration such that when the snapshot is restored later, the clock will be reset. To make
 * this work well, the process looks like
 * 1. Just before capturing the snapshot, call `advance(Duration.ZERO)` to update the base real
 *    time.
 * 2. Capture the snapshot.
 * 3. Later on, restore the snapshot.
 * 4. Before starting the server, execute `UPDATE test_clock SET real_time = NOW();` to update the
 *    base real time again. This effectively undoes the passage of time since step 1.
 * 5. Start the server. The clock will appear to have advanced by only a few seconds.
 */
@Named
class DatabaseBackedClock
private constructor(
    private val dslContext: DSLContext,
    private val publisher: ApplicationEventPublisher,
    private val systemUser: SystemUser,
    private val timeZone: ZoneId,
    private val useTestClock: Boolean = false,
    private val systemClock: Clock = system(timeZone),
    /**
     * How often to poll for changes to the clock offset. This is needed to support setting the
     * clock in clustered environments. This can screw up tests since it launches a background timer
     * job; set to null to disable.
     */
    private val refreshInterval: Duration? = Duration.ofSeconds(5),
    private var baseRealTime: Instant = systemClock.instant(),
    private var baseFakeTime: Instant = baseRealTime,
) : Clock() {
  @Inject
  constructor(
      config: TerrawareServerConfig,
      dslContext: DSLContext,
      publisher: ApplicationEventPublisher,
      systemUser: SystemUser,
      refreshInterval: Duration? = Duration.ofSeconds(5),
  ) : this(
      dslContext,
      publisher,
      systemUser,
      config.timeZone,
      config.useTestClock,
      refreshInterval = refreshInterval,
  )

  private val log = perClassLogger()

  private var refreshTask: Timer? = null

  /**
   * Loads the clock settings from the database after database migrations have finished. This allows
   * us to use migrations to update the clock configuration.
   */
  @EventListener
  @Order(1)
  fun initialize(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    if (useTestClock) {
      readFromDatabase()

      refreshTask =
          refreshInterval?.toMillis()?.let { refreshMillis ->
            timer(period = refreshMillis) { readFromDatabase() }
          }
    }
  }

  @PreDestroy
  fun shutDownRefreshTask() {
    refreshTask?.cancel()
  }

  private fun readFromDatabase() {
    if (useTestClock) {
      val record = dslContext.selectFrom(TEST_CLOCK).fetchOne()
      if (record != null) {
        if (baseFakeTime != record.fakeTime || baseRealTime != record.realTime) {
          val offset =
              synchronized(this) {
                baseFakeTime = record.fakeTime!!
                baseRealTime = record.realTime!!
                Duration.between(baseRealTime, baseFakeTime)
              }

          log.info("Clock offset is now $offset; fake time is ${instant()}")
        }
      } else {
        log.warn("Test clock is not initialized; setting it to the current time")
        systemUser.run { advance(Duration.ZERO) }
      }
    }
  }

  override fun getZone(): ZoneId {
    return systemClock.zone
  }

  override fun withZone(zone: ZoneId): Clock {
    return synchronized(this) {
      DatabaseBackedClock(
          dslContext,
          publisher,
          systemUser,
          zone,
          useTestClock,
          systemClock.withZone(zone),
          refreshInterval,
          baseRealTime,
          baseFakeTime,
      )
    }
  }

  override fun instant(): Instant {
    return if (useTestClock) {
      synchronized(this) { baseFakeTime + Duration.between(baseRealTime, systemClock.instant()) }
    } else {
      systemClock.instant()
    }
  }

  /**
   * Advances the clock to a specific time. The new time must be later than the clock's current
   * time.
   */
  fun setFakeTime(fakeTime: Instant) {
    requirePermissions { setTestClock() }

    advance(Duration.between(instant(), fakeTime))
  }

  /**
   * Advances the clock by an amount of time. This also updates the base real time which is useful
   * to do just before capturing a database snapshot (see class documentation). Call with
   * [Duration.ZERO] to update the base real time without advancing the clock.
   */
  fun advance(duration: Duration) {
    requirePermissions { setTestClock() }

    if (!useTestClock) {
      throw IllegalStateException("Test clock is not enabled")
    }

    if (duration < Duration.ZERO) {
      throw IllegalArgumentException("Cannot set clock back to an earlier time")
    }

    val offset = Duration.between(baseRealTime, baseFakeTime) + duration
    updateFakeTimeOffset(offset)

    publisher.publishEvent(ClockAdvancedEvent(duration))
  }

  /** Resets the clock to the current system time. */
  fun reset() {
    requirePermissions { setTestClock() }

    if (!useTestClock) {
      throw IllegalStateException("Test clock is not enabled")
    }

    updateFakeTimeOffset(Duration.ZERO)

    publisher.publishEvent(ClockResetEvent())
  }

  private fun updateFakeTimeOffset(offset: Duration) {
    val realTime = systemClock.instant()
    val fakeTime = realTime + offset

    dslContext.transaction { _ ->
      dslContext.deleteFrom(TEST_CLOCK).execute()
      dslContext
          .insertInto(TEST_CLOCK)
          .set(TEST_CLOCK.FAKE_TIME, fakeTime)
          .set(TEST_CLOCK.REAL_TIME, realTime)
          .execute()
    }

    synchronized(this) {
      baseRealTime = realTime
      baseFakeTime = fakeTime
    }
  }
}
