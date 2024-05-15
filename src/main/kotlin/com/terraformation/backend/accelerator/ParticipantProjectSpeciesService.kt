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

      val checkedProjectIds = emptySet<ProjectId>()

      existingModels.forEach {
        // A submission must exist for every project that is getting a new species assigned
        if (checkedProjectIds.contains(it.projectId)) {
          return@forEach
        }

        val deliverableSubmission =
            submissionStore.fetchActiveSpeciesDeliverableSubmission(it.projectId)
        if (deliverableSubmission.submissionId == null) {
          submissionStore.createSubmission(deliverableSubmission.deliverableId, it.projectId)
        }

        checkedProjectIds.plus(it.projectId)

        eventPublisher.publishEvent(
            ParticipantProjectSpeciesAddedEvent(
                deliverableId = deliverableSubmission.deliverableId,
                participantProjectSpecies = it))
      }

      existingModels
    }
  }
}
