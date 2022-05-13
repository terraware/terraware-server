package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.AccessionDryingEndEvent
import com.terraformation.backend.customer.event.AccessionGerminationTestEvent
import com.terraformation.backend.customer.event.AccessionMoveToDryEvent
import com.terraformation.backend.customer.event.AccessionWithdrawalEvent
import com.terraformation.backend.daily.DailyTaskRunner
import com.terraformation.backend.daily.TimePeriodTask
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionNotificationStore
import com.terraformation.backend.seedbank.db.AccessionStore
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
    private val messages: Messages,
    private val accessionNotificationStore: AccessionNotificationStore,
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

    dslContext.transaction { _ ->
      moveToDryingCabinet(since, until)
      endDrying(since, until)
      germinationTest(since, until)
      withdraw(since, until)
    }
  }

  private fun moveToDryingCabinet(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchDryingMoveDue(after, until).forEach { (number, id) ->
      insert(id, messages.dryingMoveDateNotification(number))
      try {
        eventPublisher.publishEvent(AccessionMoveToDryEvent(number, id))
      } catch (e: Exception) {
        log.error("Error handling AccessionMoveToDryEvent", e)
      }
    }
  }

  private fun endDrying(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchDryingEndDue(after, until).forEach { (number, id) ->
      try {
        eventPublisher.publishEvent(AccessionDryingEndEvent(number, id))
      } catch (e: Exception) {
        log.error("Error handling AccessionDryingEndEvent", e)
      }
    }
  }

  private fun germinationTest(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchGerminationTestDue(after, until).forEach { (number, test) ->
      insert(test.accessionId!!, messages.germinationTestDateNotification(number, test.testType!!))
      try {
        eventPublisher.publishEvent(
            AccessionGerminationTestEvent(number, test.accessionId!!, test.testType!!))
      } catch (e: Exception) {
        log.error("Error handling AccessionGerminationTestEvent", e)
      }
    }
  }

  private fun withdraw(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchWithdrawalDue(after, until).forEach { (number, id) ->
      insert(id, messages.withdrawalDateNotification(number))
      try {
        eventPublisher.publishEvent(AccessionWithdrawalEvent(number, id))
      } catch (e: Exception) {
        log.error("Error handling AccessionWithdrawalEvent", e)
      }
    }
  }

  private fun insert(accessionId: AccessionId, message: String) {
    log.info("Generated notification: $message")
    accessionNotificationStore.insertDateNotification(accessionId, message)
  }

  class FinishedEvent
}
