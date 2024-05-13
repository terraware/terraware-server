package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

@Named
class ParticipantProjectSpeciesService(
    private val clock: InstantSource,
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
              modifiedTime = existingModel.modifiedTime!!,
              projectId = existingModel.projectId,
              speciesId = existingModel.speciesId))

      existingModel
    }
  }

  /**
   * Creates a participant project species for each projectId - speciesId combination and create a
   * species deliverable submission for each project that doesn't have one
   */
  fun create(projectIds: Set<ProjectId>, speciesIds: Set<SpeciesId>) {
    dslContext.transaction { _ ->
      participantProjectSpeciesStore.create(projectIds, speciesIds)

      projectIds.forEach { projectId ->
        // A submission must exist for every project that is getting a new species assigned
        val deliverableSubmission =
            submissionStore.fetchActiveSpeciesDeliverableSubmission(projectId)
        if (deliverableSubmission.submissionId == null) {
          submissionStore.createSubmission(deliverableSubmission.deliverableId, projectId)
        }

        speciesIds.forEach { speciesId ->
          eventPublisher.publishEvent(
              ParticipantProjectSpeciesAddedEvent(
                  deliverableId = deliverableSubmission.deliverableId,
                  modifiedTime = clock.instant(),
                  projectId = projectId,
                  speciesId = speciesId))
        }
      }
    }
  }
}
