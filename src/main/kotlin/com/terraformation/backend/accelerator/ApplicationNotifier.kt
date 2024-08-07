package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ApplicationReviewedEvent
import com.terraformation.backend.accelerator.event.ApplicationStatusUpdatedEvent
import com.terraformation.backend.accelerator.model.ExternalApplicationStatus
import com.terraformation.backend.customer.model.SystemUser
import jakarta.inject.Named
import java.time.Duration
import java.time.InstantSource
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

@Named
class ApplicationNotifier(
    private val clock: InstantSource,
    private val applicationStore: ApplicationStore,
    private val eventPublisher: ApplicationEventPublisher,
    @Lazy private val scheduler: JobScheduler,
    private val systemUser: SystemUser,
) {
  companion object {
    /**
     * Wait this long before notifying about new application review to give the user time to submit
     * additional status changes
     */
    private val notificationDelay = Duration.ofMinutes(5)
  }

  /** Schedules a "ready for review" notification when a document is uploaded. */
  @EventListener
  fun on(event: ApplicationReviewedEvent) {
    scheduler.schedule<ApplicationNotifier>(clock.instant().plus(notificationDelay)) {
      notifyIfNoNewerStatus(event)
    }
  }

  /** Publishes [ApplicationStatusUpdatedEvent] if status has not been updated. */
  fun notifyIfNoNewerStatus(event: ApplicationReviewedEvent) {
    systemUser.run {
      val application = applicationStore.fetchOneById(event.applicationId)

      if (ExternalApplicationStatus.of(application.status) == event.applicationStatus) {
        eventPublisher.publishEvent(
            ApplicationStatusUpdatedEvent(event.applicationId, event.applicationStatus))
      }
    }
  }
}
