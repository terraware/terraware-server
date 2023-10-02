package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.ClockAdvancedEvent
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationUpcomingNotificationDueEvent
import com.terraformation.backend.tracking.event.ReminderToScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import jakarta.inject.Inject
import jakarta.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.scheduling.cron.Cron
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class ObservationScheduler(
    private val config: TerrawareServerConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val observationService: ObservationService,
    private val observationStore: ObservationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      scheduler.scheduleRecurrently<ObservationScheduler>(
          javaClass.simpleName, Cron.every15minutes()) {
            transitionObservations()
          }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun transitionObservations() {
    systemUser.run {
      startObservations(observationStore.fetchStartableObservations())
      markObservationsOverdue(observationStore.fetchObservationsPastEndDate())
      notifyUpcomingObservations(observationStore.fetchNonNotifiedUpcomingObservations())
      notifyScheduleObservationsForSites()
    }
  }

  private fun startObservations(observations: Collection<ExistingObservationModel>) {
    observations.forEach { observation ->
      try {
        observationService.startObservation(observation.id)
      } catch (e: Exception) {
        log.error("Unable to start observation ${observation.id}", e)
      }
    }
  }

  private fun markObservationsOverdue(observations: Collection<ExistingObservationModel>) {
    observations.forEach { observation ->
      try {
        observationStore.updateObservationState(observation.id, ObservationState.Overdue)
      } catch (e: Exception) {
        log.error("Unable to mark observation ${observation.id} overdue", e)
      }
    }
  }

  private fun notifyUpcomingObservations(observations: Collection<ExistingObservationModel>) {
    observations.forEach { observation ->
      try {
        eventPublisher.publishEvent(ObservationUpcomingNotificationDueEvent(observation))
        observationStore.markUpcomingNotificationComplete(observation.id)
      } catch (e: Exception) {
        log.error("Unable to mark observation ${observation.id} upcoming notification complete")
      }
    }
  }

  private fun notifyScheduleObservationsForSites() {
    notifyScheduleObservation(observationService.fetchNonNotifiedSitesToScheduleObservations())
    notifyScheduleObservationReminder(
        observationService.fetchNonNotifiedSitesToRemindSchedulingObservations())
  }

  private fun notifyScheduleObservation(sites: Collection<PlantingSiteId>) {
    sites.forEach { site ->
      try {
        eventPublisher.publishEvent(ScheduleObservationNotificationEvent(site))
        plantingSiteStore.markScheduleObservationNotificationComplete(site)
      } catch (e: Exception) {
        log.error("Unable to mark planting site ${site} schedule observation complete")
      }
    }
  }

  private fun notifyScheduleObservationReminder(sites: Collection<PlantingSiteId>) {
    sites.forEach { site ->
      try {
        eventPublisher.publishEvent(ReminderToScheduleObservationNotificationEvent(site))
        plantingSiteStore.markReminderToScheduleObservationNotificationComplete(site)
      } catch (e: Exception) {
        log.error("Unable to mark planting site ${site} reminder to schedule observation complete")
      }
    }
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    transitionObservations()
  }

  @EventListener
  fun on(event: PlantingSiteTimeZoneChangedEvent) {
    systemUser.run {
      startObservations(observationStore.fetchStartableObservations(event.plantingSite.id))
      markObservationsOverdue(observationStore.fetchObservationsPastEndDate(event.plantingSite.id))
    }
  }
}
