package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class SpeciesNotifier(
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
    private val submissionStore: SubmissionStore,
) {
  /** Schedules a "species added" notification when a new species is added to a project. */
  @EventListener
  fun on(event: ParticipantProjectSpeciesAddedEvent) {
    rateLimitedEventPublisher.publishEvent(
        ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
            deliverableId = event.deliverableId,
            projectId = event.participantProjectSpecies.projectId,
            speciesId = event.participantProjectSpecies.speciesId,
        )
    )
  }

  /** Schedules a "species edited" notification when a species associated to a project is edited. */
  @EventListener
  fun on(event: ParticipantProjectSpeciesEditedEvent) {
    val old = event.oldParticipantProjectSpecies
    val new = event.newParticipantProjectSpecies

    if (old.submissionStatus == SubmissionStatus.Approved && old != new) {
      val deliverableSubmission =
          submissionStore.fetchMostRecentSpeciesDeliverableSubmission(event.projectId)

      if (deliverableSubmission != null) {
        rateLimitedEventPublisher.publishEvent(
            ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
                deliverableSubmission.deliverableId,
                event.projectId,
                event.newParticipantProjectSpecies.speciesId,
            )
        )
      }
    }
  }
}
