package com.terraformation.seedbank.daily

import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import org.springframework.context.event.EventListener

/**
 * Generates notifications about accession state changes.
 *
 * This is just a stub for now, to demonstrate how a daily task can depend on another daily task.
 */
@ManagedBean
class StateSummaryNotificationTask {
  private val log = perClassLogger()

  @EventListener
  fun generateNotifications(
      @Suppress("UNUSED_PARAMETER") event: AccessionScheduledStateTask.FinishedEvent
  ): FinishedEvent {
    log.debug("TODO: Generate state summary notifications")
    return FinishedEvent()
  }

  /**
   * Published when the system has finished generating notifications with summaries of accession
   * states.
   */
  class FinishedEvent
}
