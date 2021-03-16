package com.terraformation.seedbank.daily

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.AccessionStore
import com.terraformation.seedbank.db.NotificationStore
import com.terraformation.seedbank.db.tables.daos.TaskProcessedTimeDao
import com.terraformation.seedbank.i18n.Messages
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class DateNotificationTask(
    private val accessionStore: AccessionStore,
    override val clock: Clock,
    private val dslContext: DSLContext,
    override val taskProcessedTimeDao: TaskProcessedTimeDao,
    private val messages: Messages,
    private val notificationStore: NotificationStore
) : TimePeriodTask {
  private val log = perClassLogger()

  @EventListener
  fun generateNotifications(
      @Suppress("UNUSED_PARAMETER") event: AccessionScheduledStateTask.FinishedEvent
  ): FinishedEvent {
    processNewWork { after, until ->
      log.info("Generating date update notifications for due dates since $after")

      dslContext.transaction { _ ->
        moveToDryingCabinet(after, until)
        germinationTest(after, until)
        withdraw(after, until)
      }
    }

    return FinishedEvent()
  }

  private fun moveToDryingCabinet(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchDryingMoveDue(after, until).forEach { (number, id) ->
      insert(id, messages.dryingMoveDateNotification(number))
    }
  }

  private fun germinationTest(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchGerminationTestDue(after, until).forEach { (number, test) ->
      insert(test.accessionId!!, messages.germinationTestDateNotification(number, test.testType!!))
    }
  }

  private fun withdraw(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchWithdrawalDue(after, until).forEach { (number, id) ->
      insert(id, messages.withdrawalDateNotification(number))
    }
  }

  private fun insert(accessionId: Long, message: String) {
    log.info("Generated notification: $message")
    notificationStore.insertDateNotification(accessionId, message)
  }

  class FinishedEvent
}
