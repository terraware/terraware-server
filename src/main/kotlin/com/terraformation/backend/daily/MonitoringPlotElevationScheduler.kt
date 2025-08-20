package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.ClockAdvancedEvent
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.mapbox.MapboxService
import jakarta.inject.Inject
import jakarta.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.scheduling.cron.Cron
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class MonitoringPlotElevationScheduler(
    private val config: TerrawareServerConfig,
    private val mapboxService: MapboxService,
    private val plantingSiteStore: PlantingSiteStore,
    private val systemUser: SystemUser,
) {
  private val MONITORING_PLOT_BATCH_SIZE = 50
  private val log = perClassLogger()

  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      scheduler.scheduleRecurrently<MonitoringPlotElevationScheduler>(
          javaClass.simpleName,
          Cron.every15minutes(),
      ) {
        updatePlotElevation(MONITORING_PLOT_BATCH_SIZE)
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun updatePlotElevation(limit: Int) {
    systemUser.run {
      val plots =
          try {
            plantingSiteStore.fetchMonitoringPlotsWithoutElevation(limit)
          } catch (e: Exception) {
            log.warn("Failed to fetch monitoring plots without elevation data: ${e.message}")
            return@run
          }

      val elevationByPlotId =
          plots
              .mapNotNull { plot ->
                val elevation =
                    try {
                      mapboxService.getElevation(plot.boundary.centroid).toBigDecimal()
                    } catch (e: Exception) {
                      log.warn(
                          "Failed to fetch elevation for monitoring plot ${plot.id}: ${e.message}"
                      )
                      return@mapNotNull null
                    }

                plot.id to elevation
              }
              .toMap()

      try {
        val rowsUpdated = plantingSiteStore.updateMonitoringPlotElevation(elevationByPlotId)
        log.info("Updated elevation for $rowsUpdated plots")
      } catch (e: Exception) {
        log.warn("Failed to write elevation to monitoring plots table: ${e.message}")
      }
    }
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    updatePlotElevation(MONITORING_PLOT_BATCH_SIZE)
  }
}
