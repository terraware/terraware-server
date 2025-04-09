package com.terraformation.backend.daily

import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.ClockAdvancedEvent
import jakarta.inject.Inject
import jakarta.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.scheduling.cron.Cron
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class ReportScheduler(
    private val config: TerrawareServerConfig,
    private val reportStore: ReportStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      scheduler.scheduleRecurrently<ReportScheduler>(javaClass.simpleName, Cron.daily()) {
        notifyUpcomingReports()
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun notifyUpcomingReports() {
    systemUser.run {
      val numReports = reportStore.notifyUpcomingReports()
      log.info("Notified $numReports upcoming reports.")
    }
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    notifyUpcomingReports()
  }
}
