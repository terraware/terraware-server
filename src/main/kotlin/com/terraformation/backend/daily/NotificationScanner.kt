package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.ClockAdvancedEvent
import jakarta.inject.Inject
import jakarta.inject.Named
import java.time.InstantSource
import java.time.LocalDate
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.scheduling.cron.Cron
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class NotificationScanner(
    private val clock: InstantSource,
    private val config: TerrawareServerConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilityStore: FacilityStore,
    private val notifiers: List<FacilityNotifier>,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      scheduler.scheduleRecurrently<NotificationScanner>(
          javaClass.simpleName,
          Cron.every15minutes(),
      ) {
        sendNotifications()
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun sendNotifications() {
    systemUser.run { facilityStore.withNotificationsDue { sendNotifications(it) } }
  }

  private fun sendNotifications(facility: FacilityModel) {
    val effectiveTimeZone = facilityStore.fetchEffectiveTimeZone(facility)
    val todayAtFacility = LocalDate.ofInstant(clock.instant(), effectiveTimeZone)

    if (facility.lastNotificationDate == null) {
      log.info("Bootstrapping last notification date for facility ${facility.id}")
      facilityStore.updateNotificationTimes(facility.copy(lastNotificationDate = todayAtFacility))
    } else if (facility.lastNotificationDate < todayAtFacility) {
      try {
        eventPublisher.publishEvent(NotificationJobStartedEvent())

        notifiers.forEach { it.sendNotifications(facility, todayAtFacility) }
        facilityStore.updateNotificationTimes(facility.copy(lastNotificationDate = todayAtFacility))

        eventPublisher.publishEvent(NotificationJobSucceededEvent())
      } finally {
        eventPublisher.publishEvent(NotificationJobFinishedEvent())
      }
    }
  }

  @EventListener
  @Order(Int.MAX_VALUE)
  fun on(@Suppress("UNUSED_PARAMETER") event: ClockAdvancedEvent) {
    sendNotifications()
  }

  @EventListener
  fun on(event: FacilityTimeZoneChangedEvent) {
    systemUser.run { sendNotifications(event.facility) }
  }
}
