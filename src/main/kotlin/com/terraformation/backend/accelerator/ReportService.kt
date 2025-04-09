package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class ReportService(
    private val reportStore: ReportStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent) {
    systemUser.run {
      try {
        val numNotified = reportStore.notifyUpcomingReports()
        log.info("Notified $numNotified upcoming reports.")
      } catch (e: Exception) {
        log.warn("Failed to notify upcoming reports: ${e.message}")
      }
    }
  }
}
