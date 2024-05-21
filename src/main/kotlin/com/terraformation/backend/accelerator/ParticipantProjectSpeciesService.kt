package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.species.event.SpeciesEditedEvent
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class ParticipantProjectSpeciesService(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val participantProjectSpeciesStore: ParticipantProjectSpeciesStore,
    private val submissionStore: SubmissionStore,
) {
  /** Creates a new participant project species, possibly creating a deliverable submission. */
  fun create(model: NewParticipantProjectSpeciesModel): ExistingParticipantProjectSpeciesModel {
    return dslContext.transactionResult { _ ->
      val existingModel = participantProjectSpeciesStore.create(model)

      // If a submission doesn't exist for the deliverable, create one
      val deliverableSubmission =
          submissionStore.fetchActiveSpeciesDeliverableSubmission(model.projectId)
      if (deliverableSubmission.submissionId == null) {
        submissionStore.createSubmission(deliverableSubmission.deliverableId, model.projectId)
      }

      eventPublisher.publishEvent(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId = deliverableSubmission.deliverableId,
              participantProjectSpecies = existingModel))

      existingModel
    }
  }

  /**
   * Creates a participant project species for each projectId - speciesId combination and create a
   * species deliverable submission for each project that doesn't have one
   */
  fun create(
      projectIds: Set<ProjectId>,
      speciesIds: Set<SpeciesId>
  ): List<ExistingParticipantProjectSpeciesModel> {
    return dslContext.transactionResult { _ ->
      val existingModels = participantProjectSpeciesStore.create(projectIds, speciesIds)

      // Used to save relatively expensive queries for projects which we know have submissions
      val projectDeliverableIds = mutableMapOf<ProjectId, DeliverableId>()

      existingModels.forEach { participantProjectSpecies ->
        projectDeliverableIds[participantProjectSpecies.projectId]?.let {
          publishAddedEvent(it, participantProjectSpecies)
          return@forEach
        }

        // A submission must exist for every project that is getting a new species assigned
        val deliverableSubmission =
            submissionStore.fetchActiveSpeciesDeliverableSubmission(
                participantProjectSpecies.projectId)
        if (deliverableSubmission.submissionId == null) {
          submissionStore.createSubmission(
              deliverableSubmission.deliverableId, participantProjectSpecies.projectId)
        }

        projectDeliverableIds[participantProjectSpecies.projectId] =
            deliverableSubmission.deliverableId
        publishAddedEvent(
            projectDeliverableIds[participantProjectSpecies.projectId]!!, participantProjectSpecies)
      }

      existingModels
    }
  }

  /*
   * When a species is updated, if it belongs to an organization with participants and is associated
   * to a participant project, we need to update its status to "in review" across all
   * associated projects. This only applies to users with no accelerator related global roles.
   */
  @EventListener
  fun on(event: SpeciesEditedEvent) {
    if (currentUser().canReadAllAcceleratorDetails()) {
      return
    }

    val projects =
        participantProjectSpeciesStore.fetchParticipantProjectsForSpecies(
            event.species.organizationId, event.species.id)

    dslContext.transaction { _ ->
      projects.forEach { project ->
        // Set all non "in review" participant project species to "in review" status
        if (project.participantProjectSpeciesSubmissionStatus !== SubmissionStatus.InReview) {
          participantProjectSpeciesStore.update(project.participantProjectSpeciesId) {
            it.copy(submissionStatus = SubmissionStatus.InReview)
          }
        }
      }
    }
  }

  private fun publishAddedEvent(
      deliverableId: DeliverableId,
      participantProjectSpecies: ExistingParticipantProjectSpeciesModel
  ) {
    eventPublisher.publishEvent(
        ParticipantProjectSpeciesAddedEvent(
            deliverableId = deliverableId, participantProjectSpecies = participantProjectSpecies))
  }
}
