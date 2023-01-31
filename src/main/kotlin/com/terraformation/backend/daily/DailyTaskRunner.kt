package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.time.ClockAdvancedEvent
import com.terraformation.backend.time.atNext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/**
 * Executes all the tasks that need to run on a daily basis. Handles the clock jumping forward by a
 * large amount suddenly, e.g., because `DatabaseBackedClock` is being used in a test environment.
 *
 * When the appointed time arrives, publishes [DailyTaskTimeArrivedEvent] on the application event
 * bus. Spring will look at all the managed beans to find methods annotated with [EventListener]
 * that take the event class as an argument, and will call those methods; the listeners may return
 * values that are themselves published as events, allowing tasks to depend on other tasks.
 */
@DailyTask
@Named
class DailyTaskRunner(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val publisher: ApplicationEventPublisher,
    private val systemUser: SystemUser,
) {
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

  /**
   * Scans for work whenever the test clock is advanced past the start time in the server's time
   * zone.
   */
  @EventListener
  fun handle(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    val now = clock.instant()

    // The Instant that would have been the next daily task time prior to the clock being advanced.
    val nextDailyTaskTime =
        (now - event.duration)
            .atZone(config.timeZone)
            .atNext(config.dailyTasks.startTime)
            .toInstant()

    if (now >= nextDailyTaskTime) {
      runDailyTasks()
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
}
