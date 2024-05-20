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
import com.terraformation.backend.db.accelerator.tables.records.ParticipantProjectSpeciesRecord
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import jakarta.inject.Named
import java.time.Instant
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.TableField
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

  fun create(
      projectIds: Set<ProjectId>,
      speciesIds: Set<SpeciesId>
  ): List<ExistingParticipantProjectSpeciesModel> {
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

    return fetch(
        PARTICIPANT_PROJECT_SPECIES.PROJECT_ID.`in`(projectIds)
            .and(PARTICIPANT_PROJECT_SPECIES.SPECIES_ID.`in`(speciesIds)))
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

  fun fetchLastCreatedSpeciesTime(projectId: ProjectId): Instant =
      fetchLastSpeciesTime(projectId, PARTICIPANT_PROJECT_SPECIES.CREATED_TIME)

  fun fetchLastModifiedSpeciesTime(projectId: ProjectId): Instant =
      fetchLastSpeciesTime(projectId, PARTICIPANT_PROJECT_SPECIES.MODIFIED_TIME)

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

    val participantProjectSpecies = fetchOneRecordById(participantProjectSpeciesId)

    val oldParticipantProjectSpecies =
        ExistingParticipantProjectSpeciesModel.of(participantProjectSpecies)

    with(participantProjectSpecies) {
      feedback = updated.feedback
      modifiedBy = currentUser().userId
      modifiedTime = clock.instant()
      rationale = updated.rationale
      submissionStatusId = updated.submissionStatus

      store()
    }

    eventPublisher.publishEvent(
        ParticipantProjectSpeciesEditedEvent(
            newParticipantProjectSpecies =
                ExistingParticipantProjectSpeciesModel.of(
                    fetchOneRecordById(participantProjectSpeciesId)),
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

  private fun fetchLastSpeciesTime(
      projectId: ProjectId,
      field: TableField<ParticipantProjectSpeciesRecord, Instant?>
  ): Instant {
    requirePermissions { readProject(projectId) }

    return with(PARTICIPANT_PROJECT_SPECIES) {
      dslContext
          .select(DSL.max(field))
          .from(this)
          .where(PROJECT_ID.eq(projectId))
          .fetchOne(DSL.max(field))
          ?: throw ParticipantProjectSpeciesProjectNotFoundException(projectId)
    }
  }

  private fun fetchOneRecordById(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): ParticipantProjectSpeciesRecord =
      dslContext.fetchOne(
          PARTICIPANT_PROJECT_SPECIES,
          PARTICIPANT_PROJECT_SPECIES.ID.eq(participantProjectSpeciesId))
          ?: throw ParticipantProjectSpeciesNotFoundException(participantProjectSpeciesId)
}
