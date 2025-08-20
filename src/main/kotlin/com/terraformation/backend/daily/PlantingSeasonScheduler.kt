package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.ClockAdvancedEvent
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.PlantingSeasonSchedulingNotificationEvent
import com.terraformation.backend.tracking.model.NotificationCriteria
import jakarta.inject.Inject
import jakarta.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.scheduling.cron.Cron
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class PlantingSeasonScheduler(
    private val config: TerrawareServerConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val plantingSiteStore: PlantingSiteStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      scheduler.scheduleRecurrently<PlantingSeasonScheduler>(
          javaClass.simpleName,
          Cron.every15minutes(),
      ) {
        transitionPlantingSeasons()
      }
    }
  }

  fun transitionPlantingSeasons() {
    systemUser.run {
      plantingSiteStore.transitionPlantingSeasons()
      sendNotifications()
    }
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    transitionPlantingSeasons()
  }

  private fun sendNotifications() {
    NotificationCriteria.FirstPlantingSeasonNotScheduled.notifications.forEach { criteria ->
      plantingSiteStore
          .fetchPartiallyPlantedDetailedSitesWithNoPlantingSeasons(
              criteria.weeksSinceCreation,
              criteria.notificationNotCompletedCondition(),
          )
          .forEach { plantingSiteId ->
            sendNotification(criteria, criteria.notificationEvent(plantingSiteId))
          }
    }

    NotificationCriteria.NextPlantingSeasonNotScheduled.notifications.forEach { criteria ->
      plantingSiteStore
          .fetchPartiallyPlantedDetailedSitesWithNoUpcomingPlantingSeasons(
              criteria.weeksSinceLastSeason,
              criteria.notificationNotCompletedCondition(),
          )
          .forEach { plantingSiteId ->
            sendNotification(criteria, criteria.notificationEvent(plantingSiteId))
          }
    }
  }

  private fun sendNotification(
      criteria: NotificationCriteria,
      event: PlantingSeasonSchedulingNotificationEvent,
  ) {
    try {
      // Mark the notification complete first to guard against concurrent attempts to send the
      // same notification, which will cause all but one of the attempts to fail with an integrity
      // constraint violation.
      plantingSiteStore.markNotificationComplete(
          event.plantingSiteId,
          criteria.notificationType,
          criteria.notificationNumber,
      )
    } catch (e: Exception) {
      log.warn(
          "Failed to mark notification complete for planting site ${event.plantingSiteId}; " +
              "not sending it",
          e,
      )

      return
    }

    eventPublisher.publishEvent(event)
  }
}
