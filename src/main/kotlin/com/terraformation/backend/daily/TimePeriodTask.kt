package com.terraformation.backend.daily

import java.time.Duration
import java.time.Instant

/**
 * A periodic task that scans for work spanning a specific time period.
 *
 * @see DailyTaskRunner
 */
interface TimePeriodTask {
  /** The maximum amount of time that can be covered by a single run. */
  val maximumPeriod: Duration
    get() = Duration.ofDays(30)

  /**
   * The maximum amount of time a task can be marked as in progress before we decide the original
   * run must have bombed out without marking itself as finished.
   */
  val timeoutPeriod: Duration
    get() = Duration.ofHours(4)

  /** How far back to start searching the first time a task is run. */
  fun startTimeForFirstRun(now: Instant): Instant = now - maximumPeriod

  /**
   * Scans a time period for work and runs whatever processes need to happen for anything it finds.
   * Invoked by [DailyTaskRunner.runTask].
   *
   * @param since Start of the time period, exclusive.
   * @param until End of the time period, inclusive.
   */
  fun processPeriod(since: Instant, until: Instant)
}
