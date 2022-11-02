package com.terraformation.backend.nursery.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskRunner
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.daily.TimePeriodTask
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.db.BatchStore
import java.time.Instant
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class NurseryDateNotificationTask(
    private val batchStore: BatchStore,
    private val dailyTaskRunner: DailyTaskRunner,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher
) : TimePeriodTask {
  private val log = perClassLogger()

  @EventListener
  fun generateNotifications(
      @Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent
  ): FinishedEvent {
    dailyTaskRunner.runTask(this)
    return FinishedEvent()
  }

  override fun processPeriod(since: Instant, until: Instant) {
    log.info("Generating date update notifications for due dates since $since")
    eventPublisher.publishEvent(StartedEvent())

    dslContext.transaction { _ -> seedlingBatchReady(since, until) }

    eventPublisher.publishEvent(SucceededEvent())
  }

  private fun seedlingBatchReady(after: TemporalAccessor, until: TemporalAccessor) {
    batchStore.fetchEstimatedReady(after, until).forEach { eventPublisher.publishEvent(it) }
  }

  /** Published when the period processed task begins. */
  class StartedEvent

  /**
   * Published when the period processed task ends successfully. This event will not be published if
   * there are errors.
   */
  class SucceededEvent

  /**
   * Published when the system has finished generating notifications for individual batches
   * regardless of error state.
   */
  class FinishedEvent
}
