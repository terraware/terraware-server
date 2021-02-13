package com.terraformation.seedbank.daily

import com.terraformation.seedbank.db.AccessionFetcher
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.springframework.context.event.EventListener

@ManagedBean
class AccessionScheduledStateTask(private val fetcher: AccessionFetcher, private val clock: Clock) {
  private val log = perClassLogger()

  /** Updates the states of any accessions that are scheduled for a time-based state transition. */
  @EventListener
  fun updateScheduledStates(
      @Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent
  ): FinishedEvent {
    log.info("Scanning for scheduled accession state updates")

    fetcher
        .fetchTimedStateTransitionCandidates()
        .filter { it.getStateTransition(it, clock) != null }
        .forEach { fetcher.update(it.accessionNumber, it) }

    return FinishedEvent()
  }

  /**
   * Published when the system has finished updating the states of all the accessions that were
   * scheduled for state changes today. Classes that need to examine the new set of states can
   * listen for this event.
   */
  class FinishedEvent
}
