package com.terraformation.seedbank.daily

import com.terraformation.seedbank.db.tables.daos.TaskProcessedTimesDao
import com.terraformation.seedbank.db.tables.pojos.TaskProcessedTimesRow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Scaffolding for periodic tasks that scan for work in specific time periods. Records the most
 * recently processed time period in the database if it was handled successfully, such that the next
 * time the task is invoked, it will only look for new work rather than overlapping with the
 * previous invocation.
 */
interface TimePeriodTask {
  val clock: Clock
  val taskProcessedTimesDao: TaskProcessedTimesDao

  /** The maximum amount of time that can be covered by a single run. */
  val maximumPeriod: Duration
    get() = Duration.ofDays(30)

  /** How far back to start searching the first time a task is run. */
  fun startTimeForFirstRun(now: Instant): Instant = now - maximumPeriod

  /**
   * Determines what time period should be scanned for new work and invokes a function to do it.
   * Records the most recently processed time period so that the next invocation won't overlap with
   * times that have already been covered.
   */
  fun processNewWork(taskName: String = javaClass.simpleName, processor: TimePeriodProcessor) {
    val now = clock.instant()
    val lastRun = taskProcessedTimesDao.fetchOneByName(taskName)
    val since = lastRun?.processedUpTo ?: startTimeForFirstRun(now)
    val until = (since + maximumPeriod).coerceAtMost(now)

    processor.processPeriod(since, until)

    val newRun = TaskProcessedTimesRow(name = javaClass.simpleName, processedUpTo = until)
    if (lastRun == null) {
      taskProcessedTimesDao.insert(newRun)
    } else {
      taskProcessedTimesDao.update(newRun)
    }
  }

  /** @see [processPeriod] */
  fun interface TimePeriodProcessor {
    /**
     * Scans a time period for work and runs whatever processes need to happen for anything it
     * finds.
     * @param since Start of the time period, exclusive.
     * @param until End of the time period, inclusive.
     */
    fun processPeriod(since: Instant, until: Instant)
  }
}
