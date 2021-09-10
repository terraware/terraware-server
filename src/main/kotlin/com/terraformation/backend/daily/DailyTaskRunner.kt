package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.tables.pojos.TaskProcessedTimesRow
import com.terraformation.backend.db.tables.references.TASK_PROCESSED_TIMES
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.annotation.ManagedBean
import javax.annotation.PreDestroy
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException

/**
 * Executes all the tasks that need to run on a daily basis. Handles the clock jumping forward by a
 * large amount suddenly, e.g., because `DatabaseBackedClock` is being used in a test environment.
 *
 * When the appointed time arrives, publishes [DailyTaskTimeArrivedEvent] on the application event
 * bus. Spring will look at all the managed beans to find methods annotated with [EventListener]
 * that take the event class as an argument, and will call those methods; the listeners may return
 * values that are themselves published as events, allowing tasks to depend on other tasks.
 */
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class DailyTaskRunner(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val publisher: ApplicationEventPublisher
) : Runnable {
  private var thread = Thread(this, "DailyTasksThread")
  private var nextRunTime = Instant.EPOCH
  private val shutdownLatch = CountDownLatch(1)

  private val log = perClassLogger()

  @EventListener
  fun startWorkerThread(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    thread.isDaemon = false
    thread.start()
  }

  @PreDestroy
  fun shutDownWorkerThread() {
    shutdownLatch.countDown()
  }

  override fun run() {
    // Poll the clock once a second to see if the scheduled time has arrived. While this isn't as
    // efficient as computing how long to sleep until the next scheduled time, it handles
    // clock jumps in test environments without the need for a more complex signaling mechanism.
    while (!shutdownLatch.await(1, TimeUnit.SECONDS)) {
      if (clock.instant() >= nextRunTime) {
        log.info("Running daily tasks")

        try {
          publisher.publishEvent(DailyTaskTimeArrivedEvent())
        } catch (e: Exception) {
          log.error("An error occurred while running daily tasks", e)
        }

        nextRunTime = computeNextRunTime()
      }
    }

    log.trace("Daily task worker thread stopped")
  }

  /**
   * Determines what time period should be scanned for new work and invokes a function to do it.
   * Records the most recently processed time period so that the next invocation won't overlap with
   * times that have already been covered.
   */
  fun runTask(task: TimePeriodTask, taskName: String = task.javaClass.simpleName) {
    val now = clock.instant()

    val since =
        dslContext.transactionResult { _ ->

          // Hold a row lock on the task_processed_times table while we check to see if we should
          // run the task. This prevents two terraware-server instances from running the same task
          // at the same time. The second one will block waiting for the row lock here, and when
          // the first one releases the lock, it will have already updated started_time.
          //
          // If this is the first time the task has ever run, there won't be an existing row for it;
          // in that case, we rely on the unique constraint on the name column to ensure that only
          // one terraware-server instance succeeds in inserting the row for the task.

          val row =
              dslContext
                  .selectFrom(TASK_PROCESSED_TIMES)
                  .where(TASK_PROCESSED_TIMES.NAME.eq(taskName))
                  .forUpdate()
                  .fetchOneInto(TaskProcessedTimesRow::class.java)

          val startedTime = row?.startedTime
          val processedUpTo = row?.processedUpTo ?: task.startTimeForFirstRun(now)

          val since: Instant? =
              if (row == null) {
                log.info("Task $taskName running for the first time")

                try {
                  dslContext
                      .insertInto(TASK_PROCESSED_TIMES)
                      .set(TASK_PROCESSED_TIMES.NAME, taskName)
                      .set(TASK_PROCESSED_TIMES.PROCESSED_UP_TO, processedUpTo)
                      .set(TASK_PROCESSED_TIMES.STARTED_TIME, now)
                      .execute()
                  processedUpTo
                } catch (e: DuplicateKeyException) {
                  log.warn(
                      "Failed to insert row for task $taskName; most likely another instance " +
                          "inserted it at the same time",
                      e)
                  null
                }
              } else if (startedTime == null) {
                processedUpTo
              } else if (startedTime.plus(task.timeoutPeriod) < now) {
                log.warn(
                    "Task $taskName running since $startedTime; assuming it bombed out so retrying")
                processedUpTo
              } else {
                log.debug("Skipping task $taskName because it is already in progress")
                null
              }

          if (since != null) {
            dslContext
                .update(TASK_PROCESSED_TIMES)
                .set(TASK_PROCESSED_TIMES.STARTED_TIME, now)
                .where(TASK_PROCESSED_TIMES.NAME.eq(taskName))
                .execute()
          }

          since
        }

    if (since != null) {
      val until = (since + task.maximumPeriod).coerceAtMost(now)

      try {
        task.processPeriod(since, until)

        try {
          dslContext
              .update(TASK_PROCESSED_TIMES)
              .set(TASK_PROCESSED_TIMES.PROCESSED_UP_TO, until)
              .setNull(TASK_PROCESSED_TIMES.STARTED_TIME)
              .where(TASK_PROCESSED_TIMES.NAME.eq(taskName))
              .execute()
        } catch (e: Exception) {
          log.error("Unable to mark task $taskName as finished", e)
        }
      } catch (e: Exception) {
        log.error(
            "Task $taskName failed for period $since - $until. Will retry on the next run.", e)

        dslContext
            .update(TASK_PROCESSED_TIMES)
            .setNull(TASK_PROCESSED_TIMES.STARTED_TIME)
            .where(TASK_PROCESSED_TIMES.NAME.eq(taskName))
            .execute()
      }
    }
  }

  private fun computeNextRunTime(): Instant {
    val now = ZonedDateTime.now(clock)
    val todayAtStartTime = now.with(config.dailyTasks.startTime)
    val nextZonedDateTime =
        if (todayAtStartTime > now) todayAtStartTime else todayAtStartTime.plusDays(1)
    val nextInstant = nextZonedDateTime.toInstant()

    log.debug("Next daily task run time is $nextInstant")
    return nextInstant
  }
}
