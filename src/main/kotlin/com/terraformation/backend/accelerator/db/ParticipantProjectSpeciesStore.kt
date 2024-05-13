package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
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
import java.time.Instant
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class ParticipantProjectSpeciesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val participantProjectSpeciesDao: ParticipantProjectSpeciesDao,
    private val projectsDao: ProjectsDao,
) {
  fun create(model: NewParticipantProjectSpeciesModel): ExistingParticipantProjectSpeciesModel {
    requirePermissions { createParticipantProjectSpecies(model.projectId) }

    // Participant project species can only be associated
    // to projects that are associated to a participant
    val project = projectsDao.fetchOneById(model.projectId)
    if (project?.participantId == null) {
      throw ProjectNotInParticipantException(model.projectId)
    }

    val userId = currentUser().userId
    val now = clock.instant()

    val row =
        ParticipantProjectSpeciesRow(
            createdBy = userId,
            createdTime = now,
            feedback = model.feedback,
            modifiedBy = userId,
            modifiedTime = now,
            projectId = model.projectId,
            rationale = model.rationale,
            speciesId = model.speciesId,
            submissionStatusId = model.submissionStatus,
        )

    participantProjectSpeciesDao.insert(row)

    return row.toModel()
  }

  fun create(projectIds: Set<ProjectId>, speciesIds: Set<SpeciesId>): Unit {
    // Participant project species can only be associated
    // to projects that are associated to a participant
    val projects = projectsDao.fetchById(*projectIds.toTypedArray())
    projects.forEach {
      requirePermissions { createParticipantProjectSpecies(it.id!!) }

      if (it.participantId == null) {
        throw ProjectNotInParticipantException(it.id!!)
      }
    }

    val userId = currentUser().userId
    val now = clock.instant()

    dslContext.transactionResult { _ ->
      projectIds.toSet().forEach { projectId ->
        speciesIds.toSet().forEach { speciesId ->
          with(PARTICIPANT_PROJECT_SPECIES) {
            dslContext
                .insertInto(PARTICIPANT_PROJECT_SPECIES)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(PROJECT_ID, projectId)
                .set(SPECIES_ID, speciesId)
                .set(SUBMISSION_STATUS_ID, SubmissionStatus.NotSubmitted)
                .onConflict()
                .doNothing()
                .execute()
          }
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

  fun fetchLastUpdatedSpeciesTime(projectId: ProjectId): Instant {
    requirePermissions { readProject(projectId) }

    val lastUpdatedTime =
        with(PARTICIPANT_PROJECT_SPECIES) {
          dslContext
              .select(DSL.max(MODIFIED_TIME))
              .from(this)
              .where(PROJECT_ID.eq(projectId))
              .fetchOne(DSL.max(MODIFIED_TIME))
              ?: throw ParticipantProjectSpeciesProjectNotFoundException(projectId)
        }

    return lastUpdatedTime
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

    val modifiedTime = clock.instant()

    val participantProjectSpecies =
        dslContext
            .selectFrom(PARTICIPANT_PROJECT_SPECIES)
            .where(PARTICIPANT_PROJECT_SPECIES.ID.eq(participantProjectSpeciesId))
            .fetchOne()
            ?: throw ParticipantProjectSpeciesNotFoundException(participantProjectSpeciesId)

    val oldParticipantProjectSpecies =
        ExistingParticipantProjectSpeciesModel.of(participantProjectSpecies)

    participantProjectSpecies.feedback = updated.feedback
    participantProjectSpecies.modifiedBy = currentUser().userId
    participantProjectSpecies.modifiedTime = modifiedTime
    participantProjectSpecies.rationale = updated.rationale
    participantProjectSpecies.submissionStatusId = updated.submissionStatus

    participantProjectSpecies.store()

    eventPublisher.publishEvent(
        ParticipantProjectSpeciesEditedEvent(
            modifiedTime = modifiedTime,
            newParticipantProjectSpecies =
                ExistingParticipantProjectSpeciesModel.of(participantProjectSpecies),
            oldParticipantProjectSpecies = oldParticipantProjectSpecies,
            projectId = participantProjectSpecies.projectId!!))
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
