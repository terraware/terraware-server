package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.accelerator.event.DeliverableSpeciesAddedEvent
import com.terraformation.backend.accelerator.event.DeliverableSpeciesEditedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesSubmittedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.Duration
import java.time.InstantSource
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

@Named
class SpeciesNotifier(
    private val clock: InstantSource,
    private val participantProjectSpeciesStore: ParticipantProjectSpeciesStore,
    private val eventPublisher: ApplicationEventPublisher,
    @Lazy private val scheduler: JobScheduler,
    private val systemUser: SystemUser,
) {
  companion object {
    /**
     * Wait this long before notifying about species-related deliverable updates. This reduces
     * duplicate notifications for a deliverable which may have many species being updated in short
     * succession. This is the delay after the _last_ notification, that is, the user-visible
     * behavior is that the countdown resets each time a species modification is made for this
     * deliverable.
     */
    private val notificationDelay = Duration.ofMinutes(10)

    private val log = perClassLogger()
  }

  /** Schedules a "species added" notification when a new species is added to a project. */
  @EventListener
  fun on(event: DeliverableSpeciesAddedEvent) {
    scheduler.schedule<SpeciesNotifier>(clock.instant().plus(notificationDelay)) {
      notifyIfNoNewerUpdates(event)
    }
  }

  /** Schedules a "species edited" notification when a species associated to a project is edited. */
  @EventListener
  fun on(event: DeliverableSpeciesEditedEvent) {
    scheduler.schedule<SpeciesNotifier>(clock.instant().plus(notificationDelay)) {
      notifyIfNoNewerUpdates(event)
    }
  }

  /**
   * Publishes [DeliverableReadyForReviewEvent] if no documents have been uploaded for a submission
   * since the one referenced by the event.
   */
  fun notifyIfNoNewerUpdates(event: DeliverableSpeciesAddedEvent) {
    systemUser.run {
      val lastUpdateTime =
          participantProjectSpeciesStore.fetchLastUpdatedSpeciesTime(event.projectId)

      if (lastUpdateTime == event.participantProjectSpecies.modifiedTime) {
        eventPublisher.publishEvent(
            ParticipantProjectSpeciesSubmittedEvent(event.deliverableId, event.projectId))
      }
    }
  }

  fun notifyIfNoNewerUpdates(event: DeliverableSpeciesEditedEvent) {
    systemUser.run {
      val lastUpdateTime =
          participantProjectSpeciesStore.fetchLastUpdatedSpeciesTime(event.projectId)

      if (lastUpdateTime == event.modifiedTime) {
        eventPublisher.publishEvent(
            ParticipantProjectSpeciesEditedEvent(event.deliverableId, event.projectId))
      }
    }
  }
}
