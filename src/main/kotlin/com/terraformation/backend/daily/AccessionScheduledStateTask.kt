package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionStore
import com.terraformation.backend.services.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class AccessionScheduledStateTask(private val store: AccessionStore, private val clock: Clock) {
  private val log = perClassLogger()

  /** Updates the states of any accessions that are scheduled for a time-based state transition. */
  @EventListener
  fun updateScheduledStates(
      @Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent
  ): FinishedEvent {
    log.info("Scanning for scheduled accession state updates")

    store
        .fetchTimedStateTransitionCandidates()
        .filter { it.getStateTransition(it, clock) != null }
        .forEach { model ->
          if (model.accessionNumber != null) {
            store.update(model.accessionNumber, model)
          }
        }

    return FinishedEvent()
  }

  /**
   * Published when the system has finished updating the states of all the accessions that were
   * scheduled for state changes today. Classes that need to examine the new set of states can
   * listen for this event.
   */
  class FinishedEvent
}
