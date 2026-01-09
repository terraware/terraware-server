package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectsForSpecies
import com.terraformation.backend.accelerator.model.SpeciesForParticipantProject
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantProjectSpeciesDao
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.records.ParticipantProjectSpeciesRecord
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.i18n.TimeZones
import jakarta.inject.Named
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
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
            speciesNativeCategoryId = model.speciesNativeCategory,
        )

    participantProjectSpeciesDao.insert(row)

    return row.toModel()
  }

  fun create(
      projectIds: Set<ProjectId>,
      speciesIds: Set<SpeciesId>,
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
            .and(PARTICIPANT_PROJECT_SPECIES.SPECIES_ID.`in`(speciesIds))
    )
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

  fun fetchParticipantProjectsForSpecies(
      speciesId: SpeciesId
  ): List<ParticipantProjectsForSpecies> {
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    val user = currentUser()

    return dslContext
        .select(
            DSL.max(DELIVERABLES.ID),
            PROJECTS.NAME,
            PROJECTS.ID,
            PARTICIPANT_PROJECT_SPECIES.ID,
            PARTICIPANT_PROJECT_SPECIES.SUBMISSION_STATUS_ID,
            PARTICIPANT_PROJECT_SPECIES.SPECIES_NATIVE_CATEGORY_ID,
            SPECIES.ID,
        )
        .from(SPECIES)
        .join(PARTICIPANT_PROJECT_SPECIES)
        .on(SPECIES.ID.eq(PARTICIPANT_PROJECT_SPECIES.SPECIES_ID))
        .join(PROJECTS)
        .on(PARTICIPANT_PROJECT_SPECIES.PROJECT_ID.eq(PROJECTS.ID))
        .leftOuterJoin(COHORT_MODULES)
        .on(
            COHORT_MODULES.COHORT_ID.eq(PROJECTS.COHORT_ID),
            COHORT_MODULES.START_DATE.lessOrEqual(today),
        )
        .leftOuterJoin(MODULES)
        .on(COHORT_MODULES.MODULE_ID.eq(MODULES.ID))
        .leftOuterJoin(DELIVERABLES)
        .on(
            DELIVERABLES.MODULE_ID.eq(MODULES.ID),
            DELIVERABLES.DELIVERABLE_TYPE_ID.eq(DeliverableType.Species),
        )
        .where(SPECIES.ID.eq(speciesId))
        .groupBy(PROJECTS.ID, PARTICIPANT_PROJECT_SPECIES.ID, SPECIES.ID)
        .orderBy(PROJECTS.ID, PARTICIPANT_PROJECT_SPECIES.ID, SPECIES.ID)
        .fetch { record ->
          if (user.canReadProjectDeliverables(record[PROJECTS.ID]!!)) {
            ParticipantProjectsForSpecies.of(record)
          } else {
            ParticipantProjectsForSpecies.of(record).copy(deliverableId = null)
          }
        }
        .filter { user.canReadProject(it.projectId) }
  }

  fun fetchSpeciesForParticipantProject(projectId: ProjectId): List<SpeciesForParticipantProject> {
    val user = currentUser()

    return dslContext
        .select(PROJECTS.asterisk(), PARTICIPANT_PROJECT_SPECIES.asterisk(), SPECIES.asterisk())
        .from(SPECIES)
        .join(PARTICIPANT_PROJECT_SPECIES)
        .on(SPECIES.ID.eq(PARTICIPANT_PROJECT_SPECIES.SPECIES_ID))
        .join(PROJECTS)
        .on(PARTICIPANT_PROJECT_SPECIES.PROJECT_ID.eq(PROJECTS.ID))
        .where(PROJECTS.ID.eq(projectId))
        .fetch { SpeciesForParticipantProject.of(it) }
        .filter { user.canReadProject(it.project.id) }
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
      updateFunc:
          (ExistingParticipantProjectSpeciesModel) -> ExistingParticipantProjectSpeciesModel,
  ) {
    requirePermissions { updateParticipantProjectSpecies(participantProjectSpeciesId) }

    val existing = fetchOneById(participantProjectSpeciesId)
    val updated = updateFunc(existing)

    val participantProjectSpecies = fetchOneRecordById(participantProjectSpeciesId)

    val oldParticipantProjectSpecies =
        ExistingParticipantProjectSpeciesModel.of(participantProjectSpecies)

    with(participantProjectSpecies) {
      feedback = updated.feedback
      internalComment = updated.internalComment
      modifiedBy = currentUser().userId
      modifiedTime = clock.instant()
      rationale = updated.rationale
      speciesNativeCategoryId = updated.speciesNativeCategory
      submissionStatusId = updated.submissionStatus

      store()
    }

    eventPublisher.publishEvent(
        ParticipantProjectSpeciesEditedEvent(
            newParticipantProjectSpecies =
                ExistingParticipantProjectSpeciesModel.of(
                    fetchOneRecordById(participantProjectSpeciesId)
                ),
            oldParticipantProjectSpecies = oldParticipantProjectSpecies,
            projectId = participantProjectSpecies.projectId!!,
        )
    )
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
      field: TableField<ParticipantProjectSpeciesRecord, Instant?>,
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
          PARTICIPANT_PROJECT_SPECIES.ID.eq(participantProjectSpeciesId),
      ) ?: throw ParticipantProjectSpeciesNotFoundException(participantProjectSpeciesId)
}
