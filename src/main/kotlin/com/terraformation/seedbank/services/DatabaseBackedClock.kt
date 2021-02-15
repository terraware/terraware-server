package com.terraformation.seedbank.services

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.references.TEST_CLOCK
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.annotation.ManagedBean
import javax.annotation.PostConstruct
import javax.inject.Inject
import org.jooq.DSLContext

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
 *
 * 1. Just before capturing the snapshot, call `setFakeTime(instant())` to update the base real
 * time.
 * 2. Capture the snapshot.
 * 3. Later on, restore the snapshot.
 * 4. Before starting the server, execute `UPDATE test_clock SET real_time = NOW();` to update the
 * base real time again. This effectively undoes the passage of time since step 1.
 * 5. Start the server. The clock will appear to have advanced by only a few seconds.
 */
@ManagedBean
class DatabaseBackedClock
private constructor(
    private val dslContext: DSLContext,
    private val timeZone: ZoneId,
    private val useTestClock: Boolean = false,
    private val systemClock: Clock = system(timeZone),
    private var baseRealTime: Instant = systemClock.instant(),
    private var baseFakeTime: Instant = baseRealTime
) : Clock() {
  @Inject
  constructor(
      dslContext: DSLContext,
      config: TerrawareServerConfig
  ) : this(dslContext, config.timeZone, config.useTestClock)

  private val log = perClassLogger()

  @PostConstruct
  fun readFromDatabase() {
    if (useTestClock) {
      val record = dslContext.selectFrom(TEST_CLOCK).fetchOne()
      if (record != null) {
        synchronized(this) {
          baseFakeTime = record.fakeTime!!
          baseRealTime = record.realTime!!
        }

        log.info(
            "Clock has been adjusted forward by ${Duration.between(baseRealTime, baseFakeTime)}; " +
                "fake time is ${instant()}")
      } else {
        log.warn("Test clock is not initialized; setting it to the current time")
        advance(Duration.ZERO)
      }
    }
  }

  override fun getZone(): ZoneId {
    return systemClock.zone
  }

  override fun withZone(zone: ZoneId): Clock {
    return synchronized(this) {
      DatabaseBackedClock(
          dslContext, zone, useTestClock, systemClock.withZone(zone), baseRealTime, baseFakeTime)
    }
  }

  override fun instant(): Instant {
    return if (useTestClock) {
      synchronized(this) { baseFakeTime + Duration.between(baseRealTime, systemClock.instant()) }
    } else {
      systemClock.instant()
    }
  }

  fun setFakeTime(fakeTime: Instant) {
    advance(Duration.between(instant(), fakeTime))
  }

  fun advance(duration: Duration) {
    if (!useTestClock) {
      throw IllegalStateException("Test clock is not enabled")
    }

    val adjustment = Duration.between(baseRealTime, baseFakeTime) + duration
    val realTime = systemClock.instant()
    val fakeTime = realTime + adjustment

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
