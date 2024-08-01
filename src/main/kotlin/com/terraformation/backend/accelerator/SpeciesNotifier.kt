package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
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
   * Publishes [ParticipantProjectSpeciesAddedToProjectNotificationDueEvent] if no species have been
   * added to a participant project since the one referenced by the event.
   */
  fun notifyIfNoNewerUpdates(event: ParticipantProjectSpeciesAddedEvent) {
    systemUser.run {
      val lastCreatedTime =
          participantProjectSpeciesStore.fetchLastCreatedSpeciesTime(
              event.participantProjectSpecies.projectId)

      if (lastCreatedTime == event.participantProjectSpecies.createdTime) {
        eventPublisher.publishEvent(
            ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
                deliverableId = event.deliverableId,
                projectId = event.participantProjectSpecies.projectId,
                speciesId = event.participantProjectSpecies.speciesId))
      }
    }
  }

  /**
   * Publishes [ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent] if no species
   * have been edited for the participant project since the one referenced by the event.
   */
  fun notifyIfNoNewerUpdates(event: ParticipantProjectSpeciesEditedEvent) {
    systemUser.run {
      val lastModifiedTime =
          participantProjectSpeciesStore.fetchLastModifiedSpeciesTime(event.projectId)

      if (lastModifiedTime == event.newParticipantProjectSpecies.modifiedTime) {
        val deliverableSubmission =
            submissionStore.fetchMostRecentSpeciesDeliverableSubmission(event.projectId)

        deliverableSubmission?.also {
          eventPublisher.publishEvent(
              ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
                  deliverableId = it.deliverableId,
                  projectId = event.projectId,
                  speciesId = event.newParticipantProjectSpecies.speciesId))
        }
      }
    }
  }
}
