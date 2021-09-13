package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskRunner
import com.terraformation.backend.daily.TimePeriodTask
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.NotificationStore
import com.terraformation.backend.seedbank.i18n.Messages
import java.time.Instant
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class DateNotificationTask(
    private val accessionStore: AccessionStore,
    private val dailyTaskRunner: DailyTaskRunner,
    private val dslContext: DSLContext,
    private val facilitiesDao: FacilitiesDao,
    private val messages: Messages,
    private val notificationStore: NotificationStore,
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
      facilitiesDao.findAll().mapNotNull { it.id }.forEach { facilityId ->
        moveToDryingCabinet(facilityId, since, until)
        germinationTest(facilityId, since, until)
        withdraw(facilityId, since, until)
      }
    }
  }

  private fun moveToDryingCabinet(
      facilityId: FacilityId,
      after: TemporalAccessor,
      until: TemporalAccessor
  ) {
    accessionStore.fetchDryingMoveDue(facilityId, after, until).forEach { (number, id) ->
      insert(facilityId, id, messages.dryingMoveDateNotification(number))
    }
  }

  private fun germinationTest(
      facilityId: FacilityId,
      after: TemporalAccessor,
      until: TemporalAccessor
  ) {
    accessionStore.fetchGerminationTestDue(facilityId, after, until).forEach { (number, test) ->
      insert(
          facilityId,
          test.accessionId!!,
          messages.germinationTestDateNotification(number, test.testType!!))
    }
  }

  private fun withdraw(facilityId: FacilityId, after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchWithdrawalDue(facilityId, after, until).forEach { (number, id) ->
      insert(facilityId, id, messages.withdrawalDateNotification(number))
    }
  }

  private fun insert(facilityId: FacilityId, accessionId: AccessionId, message: String) {
    log.info("Generated notification: $message")
    notificationStore.insertDateNotification(facilityId, accessionId, message)
  }

  class FinishedEvent
}
