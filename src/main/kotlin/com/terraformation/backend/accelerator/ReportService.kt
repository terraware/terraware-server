package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class ReportService(
    private val reportStore: ReportStore,
    private val systemUser: SystemUser,
) {
  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent) {
    systemUser.run { reportStore.notifyUpcomingReports() }
  }
}
