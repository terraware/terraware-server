package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.SubmissionStatus
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
    private val submissionStore: SubmissionStore,
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
  }

  /** Schedules a "species added" notification when a new species is added to a project. */
  @EventListener
  fun on(event: ParticipantProjectSpeciesAddedEvent) {
    scheduler.schedule<SpeciesNotifier>(clock.instant().plus(notificationDelay)) {
      notifyIfNoNewerUpdates(event)
    }
  }

  /** Schedules a "species edited" notification when a species associated to a project is edited. */
  @EventListener
  fun on(event: ParticipantProjectSpeciesEditedEvent) {
    val old = event.oldParticipantProjectSpecies
    val new = event.newParticipantProjectSpecies

    if (old.submissionStatus == SubmissionStatus.Approved && old != new) {
      scheduler.schedule<SpeciesNotifier>(clock.instant().plus(notificationDelay)) {
        notifyIfNoNewerUpdates(event)
      }
    }
  }

  /**
   * Publishes [DeliverableReadyForReviewEvent] if no documents have been uploaded for a submission
   * since the one referenced by the event.
   */
  private fun notifyIfNoNewerUpdates(event: ParticipantProjectSpeciesAddedEvent) {
    systemUser.run {
      val lastUpdateTime =
          participantProjectSpeciesStore.fetchLastUpdatedSpeciesTime(event.projectId)

      if (lastUpdateTime == event.modifiedTime) {
        eventPublisher.publishEvent(
            ParticipantProjectSpeciesAddedToProjectEvent(
                deliverableId = event.deliverableId,
                projectId = event.projectId,
                speciesId = event.speciesId))
      }
    }
  }

  private fun notifyIfNoNewerUpdates(event: ParticipantProjectSpeciesEditedEvent) {
    systemUser.run {
      val lastUpdateTime =
          participantProjectSpeciesStore.fetchLastUpdatedSpeciesTime(event.projectId)

      if (lastUpdateTime == event.modifiedTime) {
        val deliverableSubmission =
            submissionStore.fetchActiveSpeciesDeliverableSubmission(event.projectId)

        eventPublisher.publishEvent(
            ParticipantProjectSpeciesApprovedSpeciesEditedEvent(
                deliverableId = deliverableSubmission.deliverableId,
                projectId = event.projectId,
                speciesId = event.newParticipantProjectSpecies.speciesId))
      }
    }
  }
}
