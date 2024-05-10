package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import jakarta.inject.Named

@Named
class ParticipantProjectSpeciesService(
    private val participantProjectSpeciesStore: ParticipantProjectSpeciesStore,
    private val submissionStore: SubmissionStore,
) {
  /** Creates a new participant project species, possibly creating a deliverable submission. */
  fun create(model: NewParticipantProjectSpeciesModel): ExistingParticipantProjectSpeciesModel {
    val existingModel = participantProjectSpeciesStore.create(model)

    // If a submission doesn't exist for the deliverable, create one
    val deliverableSubmission =
        submissionStore.fetchActiveSpeciesDeliverableSubmission(model.projectId)
    if (deliverableSubmission.submissionId == null) {
      submissionStore.createSubmission(deliverableSubmission.deliverableId, model.projectId)
    }

    return existingModel
  }

  /**
   * Creates a participant project species for each projectId - speciesId combination and create a
   * species deliverable submission for each project that doesn't have one
   */
  fun create(projectIds: Set<ProjectId>, speciesIds: Set<SpeciesId>): Unit {
    participantProjectSpeciesStore.create(projectIds, speciesIds)

    projectIds.forEach {
      // A submission must exist for every project that is getting a new species assigned
      val deliverableSubmission = submissionStore.fetchActiveSpeciesDeliverableSubmission(it)
      if (deliverableSubmission.submissionId == null) {
        submissionStore.createSubmission(deliverableSubmission.deliverableId, it)
      }
    }
  }
}
