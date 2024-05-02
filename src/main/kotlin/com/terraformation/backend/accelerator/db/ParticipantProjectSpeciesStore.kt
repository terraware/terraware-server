package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantProjectSpeciesDao
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantsRow
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class ParticipantProjectSpeciesStore(
  private val dslContext: DSLContext,
  private val participantProjectSpeciesDao: ParticipantProjectSpeciesDao,
  private val projectsDao: ProjectsDao,
) {
  fun create(model: NewParticipantProjectSpeciesModel): ExistingParticipantProjectSpeciesModel {
    requirePermissions { createParticipantProjectSpecies(model.projectId) }

    // Participant project species can only be associated
    // to projects that are associated to a participant
    val project = projectsDao.fetchOneById(model.projectId);
    if (project?.participantId == null) {
      throw ProjectNotInParticipantException(model.projectId);
    }

    return dslContext.transactionResult { _ ->
      val row =
          ParticipantProjectSpeciesRow(
              feedback = model.feedback,
              projectId = model.projectId,
              rationale = model.rationale,
              speciesId = model.speciesId,
              submissionStatusId = model.submissionStatus,
          )

      participantProjectSpeciesDao.insert(row)

      row.toModel()
    }
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
