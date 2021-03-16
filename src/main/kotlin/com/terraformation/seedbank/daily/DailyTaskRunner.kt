package com.terraformation.seedbank.daily

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.annotation.ManagedBean
import javax.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationStartedEvent
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
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class DailyTaskRunner(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
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
