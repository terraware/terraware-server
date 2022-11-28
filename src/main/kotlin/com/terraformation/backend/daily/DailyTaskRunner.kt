package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.default_schema.tables.pojos.TaskProcessedTimesRow
import com.terraformation.backend.db.default_schema.tables.references.TASK_PROCESSED_TIMES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.ClockAdvancedEvent
import com.terraformation.backend.time.ClockResetEvent
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
@Named
class DailyTaskRunner(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val publisher: ApplicationEventPublisher,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  /**
   * Registers the scheduled job to run once a day. If the job is already scheduled, this is a
   * no-op.
   */
  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      val cronSchedule =
          "${config.dailyTasks.startTime.minute} ${config.dailyTasks.startTime.hour} * * *"
      scheduler.scheduleRecurrently(javaClass.simpleName, cronSchedule, config.timeZone) {
        runDailyTasks()
      }
    }
  }

  /** Scans for work whenever the application clock is adjusted. */
  @EventListener
  fun handle(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    runDailyTasks()
  }

  /**
   * Resets the last runtime of the daily tasks when the application clock is reset. This is needed
   * so that subsequent runs will see data that's added after the clock is reset.
   */
  @EventListener
  fun handle(@Suppress("UNUSED_PARAMETER") event: ClockResetEvent) {
    val now = clock.instant()
    dslContext.transaction { _ ->
      dslContext
          .update(TASK_PROCESSED_TIMES)
          .set(TASK_PROCESSED_TIMES.PROCESSED_UP_TO, now)
          .where(TASK_PROCESSED_TIMES.PROCESSED_UP_TO.gt(now))
          .execute()
    }
  }

  /**
   * Runs all the daily tasks as the system user. This publishes an application event on the local
   * Spring event bus; registered event listeners are called synchronously.
   */
  @Suppress("MemberVisibilityCanBePrivate") // Needs to be public for scheduler
  fun runDailyTasks() {
    systemUser.run { publisher.publishEvent(DailyTaskTimeArrivedEvent()) }
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
}
