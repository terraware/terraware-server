package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskRunner
import com.terraformation.backend.daily.TimePeriodTask
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.seedbank.event.AccessionGerminationTestEvent
import com.terraformation.backend.seedbank.event.AccessionMoveToDryEvent
import com.terraformation.backend.seedbank.event.AccessionWithdrawalEvent
import java.time.Instant
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class DateNotificationTask(
    private val accessionStore: AccessionStore,
    private val dailyTaskRunner: DailyTaskRunner,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher
) : TimePeriodTask {
  private val log = perClassLogger()

  @EventListener
  fun generateNotifications(
      @Suppress("UNUSED_PARAMETER") event: AccessionScheduledStateTask.FinishedEvent
  ): FinishedEvent {
    dailyTaskRunner.runTask(this)
    return FinishedEvent()
  }

  override fun processPeriod(since: Instant, until: Instant) {
    log.info("Generating date update notifications for due dates since $since")
    eventPublisher.publishEvent(StartedEvent())

    dslContext.transaction { _ ->
      moveToDryingCabinet(since, until)
      endDrying(since, until)
      germinationTest(since, until)
      withdraw(since, until)
    }

    eventPublisher.publishEvent(SucceededEvent())
  }

  private fun moveToDryingCabinet(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchDryingMoveDue(after, until).forEach { (number, id) ->
      eventPublisher.publishEvent(AccessionMoveToDryEvent(number, id))
    }
  }

  private fun endDrying(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchDryingEndDue(after, until).forEach { (number, id) ->
      eventPublisher.publishEvent(AccessionDryingEndEvent(number, id))
    }
  }

  private fun germinationTest(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchGerminationTestDue(after, until).forEach { (number, test) ->
      eventPublisher.publishEvent(
          AccessionGerminationTestEvent(number, test.accessionId!!, test.testType!!))
    }
  }

  private fun withdraw(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchWithdrawalDue(after, until).forEach { (number, id) ->
      eventPublisher.publishEvent(AccessionWithdrawalEvent(number, id))
    }
  }

  /** Published when the period processed task begins */
  class StartedEvent

  /**
   * Published when the period processed task ends successfully, this event will not be published if
   * there are errors
   */
  class SucceededEvent

  /**
   * Published when the system has finished generating notifications for individual accessions
   * regardless of error state.
   */
  class FinishedEvent
}
