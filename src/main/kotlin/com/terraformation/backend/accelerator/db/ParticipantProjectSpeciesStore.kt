package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantProjectSpeciesDao
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class ParticipantProjectSpeciesStore(
    private val dslContext: DSLContext,
    private val participantProjectSpeciesDao: ParticipantProjectSpeciesDao,
    private val projectsDao: ProjectsDao,
    private val submissionStore: SubmissionStore,
) {
  fun create(model: NewParticipantProjectSpeciesModel): ExistingParticipantProjectSpeciesModel {
    requirePermissions { createParticipantProjectSpecies(model.projectId) }

    // Participant project species can only be associated
    // to projects that are associated to a participant
    val project = projectsDao.fetchOneById(model.projectId)
    if (project?.participantId == null) {
      throw ProjectNotInParticipantException(model.projectId)
    }

    val row =
        ParticipantProjectSpeciesRow(
            feedback = model.feedback,
            projectId = model.projectId,
            rationale = model.rationale,
            speciesId = model.speciesId,
            submissionStatusId = model.submissionStatus,
        )

    participantProjectSpeciesDao.insert(row)

    // If a submission doesn't exist for the deliverable, create one
    val deliverableSubmission =
        submissionStore.fetchActiveSpeciesDeliverableSubmission(model.projectId)
    if (deliverableSubmission.submissionId == null) {
      submissionStore.createSubmission(deliverableSubmission.deliverableId, model.projectId)
    }

    return row.toModel()
  }

  fun createMany(projectIds: Set<ProjectId>, speciesIds: Set<SpeciesId>): Unit {
    // Participant project species can only be associated
    // to projects that are associated to a participant
    val projects = projectsDao.fetchById(*projectIds.toTypedArray())
    projects.forEach {
      requirePermissions { createParticipantProjectSpecies(it.id!!) }

      if (it.participantId == null) {
        throw ProjectNotInParticipantException(it.id!!)
      }
    }

    dslContext.transactionResult { _ ->
      projectIds.toSet().forEach { projectId ->
        speciesIds.toSet().forEach { speciesId ->
          with(PARTICIPANT_PROJECT_SPECIES) {
            dslContext
                .insertInto(PARTICIPANT_PROJECT_SPECIES)
                .set(PROJECT_ID, projectId)
                .set(SPECIES_ID, speciesId)
                .set(SUBMISSION_STATUS_ID, SubmissionStatus.NotSubmitted)
                .onConflict()
                .doNothing()
                .execute()
          }
        }

        // A submission must exist for every project that is getting a new species assigned
        val deliverableSubmission =
            submissionStore.fetchActiveSpeciesDeliverableSubmission(projectId)
        if (deliverableSubmission.submissionId == null) {
          submissionStore.createSubmission(deliverableSubmission.deliverableId, projectId)
        }
      }
    }
  }

  fun delete(participantProjectSpeciesIds: Set<ParticipantProjectSpeciesId>) {
    participantProjectSpeciesIds.forEach {
      requirePermissions { deleteParticipantProjectSpecies(it) }
    }

    dslContext
        .deleteFrom(PARTICIPANT_PROJECT_SPECIES)
        .where(PARTICIPANT_PROJECT_SPECIES.ID.`in`(participantProjectSpeciesIds))
        .execute()
  }

  fun fetchOneById(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): ExistingParticipantProjectSpeciesModel {
    return fetch(PARTICIPANT_PROJECT_SPECIES.ID.eq(participantProjectSpeciesId)).firstOrNull()
        ?: throw ParticipantProjectSpeciesNotFoundException(participantProjectSpeciesId)
  }

  fun findAllForProject(projectId: ProjectId): List<ExistingParticipantProjectSpeciesModel> {
    return fetch(PARTICIPANT_PROJECT_SPECIES.PROJECT_ID.eq(projectId))
  }

  fun update(
      participantProjectSpeciesId: ParticipantProjectSpeciesId,
      updateFunc: (ExistingParticipantProjectSpeciesModel) -> ExistingParticipantProjectSpeciesModel
  ) {
    requirePermissions { updateParticipantProjectSpecies(participantProjectSpeciesId) }

    val existing = fetchOneById(participantProjectSpeciesId)
    val updated = updateFunc(existing)

    dslContext.transaction { _ ->
      val rowsUpdated =
          with(PARTICIPANT_PROJECT_SPECIES) {
            dslContext
                .update(PARTICIPANT_PROJECT_SPECIES)
                .set(RATIONALE, updated.rationale)
                .set(FEEDBACK, updated.feedback)
                .set(SUBMISSION_STATUS_ID, updated.submissionStatus)
                .where(ID.eq(participantProjectSpeciesId))
                .execute()
          }

      if (rowsUpdated < 1) {
        throw ParticipantProjectSpeciesNotFoundException(participantProjectSpeciesId)
      }
    }
  }

  private fun fetch(condition: Condition?): List<ExistingParticipantProjectSpeciesModel> {
    val user = currentUser()

    return with(PARTICIPANT_PROJECT_SPECIES) {
      dslContext
          .select(PARTICIPANT_PROJECT_SPECIES.asterisk())
          .from(PARTICIPANT_PROJECT_SPECIES)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { ParticipantProjectSpeciesModel.of(it) }
          .filter { user.canReadParticipantProjectSpecies(it.id) }
    }
  }
}
