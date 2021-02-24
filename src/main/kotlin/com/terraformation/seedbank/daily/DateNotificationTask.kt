package com.terraformation.seedbank.daily

import com.terraformation.seedbank.db.AccessionFetcher
import com.terraformation.seedbank.db.NotificationFetcher
import com.terraformation.seedbank.db.tables.daos.TaskProcessedTimeDao
import com.terraformation.seedbank.i18n.Messages
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@ManagedBean
class DateNotificationTask(
    private val accessionFetcher: AccessionFetcher,
    override val clock: Clock,
    private val dslContext: DSLContext,
    override val taskProcessedTimeDao: TaskProcessedTimeDao,
    private val messages: Messages,
    private val notificationFetcher: NotificationFetcher
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
    accessionFetcher.fetchDryingMoveDue(after, until).forEach { (number, id) ->
      insert(id, messages.dryingMoveDateNotification(number))
    }
  }

  private fun germinationTest(after: TemporalAccessor, until: TemporalAccessor) {
    accessionFetcher.fetchGerminationTestDue(after, until).forEach { (number, test) ->
      insert(test.accessionId!!, messages.germinationTestDateNotification(number, test.testType!!))
    }
  }

  private fun withdraw(after: TemporalAccessor, until: TemporalAccessor) {
    accessionFetcher.fetchWithdrawalDue(after, until).forEach { (number, id) ->
      insert(id, messages.withdrawalDateNotification(number))
    }
  }

  private fun insert(accessionId: Long, message: String) {
    log.info("Generated notification: $message")
    notificationFetcher.insertDateNotification(accessionId, message)
  }

  class FinishedEvent
}
