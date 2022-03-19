package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskRunner
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.daily.TimePeriodTask
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionStore
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class AccessionScheduledStateTask(
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val dailyTaskRunner: DailyTaskRunner,
) : TimePeriodTask {
  private val log = perClassLogger()

  /** Updates the states of any accessions that are scheduled for a time-based state transition. */
  @EventListener
  fun updateScheduledStates(
      @Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent
  ): FinishedEvent {
    dailyTaskRunner.runTask(this)
    return FinishedEvent()
  }

  override fun processPeriod(since: Instant, until: Instant) {
    log.info("Scanning for scheduled accession state updates")

    accessionStore
        .fetchTimedStateTransitionCandidates()
        .filter { it.getStateTransition(it, clock) != null }
        .forEach { model ->
          if (model.accessionNumber != null) {
            accessionStore.update(model)
          }
        }
  }

  /**
   * Published when the system has finished updating the states of all the accessions that were
   * scheduled for state changes today. Classes that need to examine the new set of states can
   * listen for this event.
   */
  class FinishedEvent
}
