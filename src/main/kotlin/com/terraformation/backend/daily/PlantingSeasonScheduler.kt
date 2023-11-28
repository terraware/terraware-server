package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.time.ClockAdvancedEvent
import com.terraformation.backend.tracking.db.PlantingSiteStore
import jakarta.inject.Inject
import jakarta.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.scheduling.cron.Cron
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class PlantingSeasonScheduler(
    private val config: TerrawareServerConfig,
    private val plantingSiteStore: PlantingSiteStore,
    private val systemUser: SystemUser,
) {
  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      scheduler.scheduleRecurrently<PlantingSeasonScheduler>(
          javaClass.simpleName, Cron.every15minutes()) {
            transitionPlantingSeasons()
          }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun transitionPlantingSeasons() {
    systemUser.run { plantingSiteStore.transitionPlantingSeasons() }
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    transitionPlantingSeasons()
  }
}
