package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionStore
import java.time.Clock
import javax.annotation.ManagedBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class AccessionScheduledStateTask(
    private val store: AccessionStore,
    private val clock: Clock,
    private val facilitiesDao: FacilitiesDao
) {
  private val log = perClassLogger()

  /** Updates the states of any accessions that are scheduled for a time-based state transition. */
  @EventListener
  fun updateScheduledStates(
      @Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent
  ): FinishedEvent {
    log.info("Scanning for scheduled accession state updates")

    facilitiesDao.findAll().mapNotNull { it.id }.forEach { facilityId ->
      store
          .fetchTimedStateTransitionCandidates(facilityId)
          .filter { it.getStateTransition(it, clock) != null }
          .forEach { model ->
            if (model.accessionNumber != null) {
              store.update(facilityId, model.accessionNumber, model)
            }
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
