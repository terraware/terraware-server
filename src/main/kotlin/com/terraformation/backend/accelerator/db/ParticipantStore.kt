package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.ParticipantService
import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.event.CohortParticipantRemovedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantModel
import com.terraformation.backend.accelerator.model.NewParticipantModel
import com.terraformation.backend.accelerator.model.ParticipantModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantsDao
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantsRow
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException

@Named
class ParticipantStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val participantsDao: ParticipantsDao,
) {
  fun fetchOneById(participantId: ParticipantId): ExistingParticipantModel {
    return fetch(PARTICIPANTS.ID.eq(participantId)).firstOrNull()
        ?: throw ParticipantNotFoundException(participantId)
  }

  fun findAll(): List<ExistingParticipantModel> {
    return fetch(null)
  }

  fun create(model: NewParticipantModel): ExistingParticipantModel {
    requirePermissions { createParticipant() }

    val now = clock.instant()
    val userId = currentUser().userId

    return dslContext.transactionResult { _ ->
      val row =
          ParticipantsRow(
              cohortId = model.cohortId,
              createdBy = userId,
              createdTime = now,
              modifiedBy = userId,
              modifiedTime = now,
              name = model.name,
          )

      participantsDao.insert(row)

      if (model.cohortId != null) {
        eventPublisher.publishEvent(CohortParticipantAddedEvent(model.cohortId, row.id!!))
      }

      row.toModel()
    }
  }

  fun delete(participantId: ParticipantId) {
    requirePermissions { deleteParticipant(participantId) }

    try {
      dslContext.transaction { _ ->
        val rowsDeleted =
            dslContext.deleteFrom(PARTICIPANTS).where(PARTICIPANTS.ID.eq(participantId)).execute()

        if (rowsDeleted == 0) {
          throw ParticipantNotFoundException(participantId)
        }
      }
    } catch (e: DataIntegrityViolationException) {
      val hasProjects = dslContext.fetchExists(PROJECTS, PROJECTS.PARTICIPANT_ID.eq(participantId))
      if (hasProjects) {
        throw ParticipantHasProjectsException(participantId)
      } else {
        throw e
      }
    }
  }

  /**
   * Updates the details of a participant. Does not update the list of projects assigned to the
   * participant; use [ParticipantService.update] instead.
   */
  fun update(
      participantId: ParticipantId,
      updateFunc: (ExistingParticipantModel) -> ExistingParticipantModel,
  ) {
    requirePermissions { updateParticipant(participantId) }

    val existing = fetchOneById(participantId)
    val updated = updateFunc(existing)

    dslContext.transaction { _ ->
      val rowsUpdated =
          with(PARTICIPANTS) {
            dslContext
                .update(PARTICIPANTS)
                .set(COHORT_ID, updated.cohortId)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .set(NAME, updated.name)
                .where(ID.eq(participantId))
                .execute()
          }

      if (rowsUpdated < 1) {
        throw ParticipantNotFoundException(participantId)
      }

      if (existing.cohortId != updated.cohortId) {
        if (existing.cohortId != null) {
          eventPublisher.publishEvent(
              CohortParticipantRemovedEvent(existing.cohortId, participantId)
          )
        }
        if (updated.cohortId != null) {
          eventPublisher.publishEvent(CohortParticipantAddedEvent(updated.cohortId, participantId))
        }
      }
    }
  }

  private fun fetch(condition: Condition?): List<ExistingParticipantModel> {
    val user = currentUser()

    val projectIdsMultiset =
        DSL.multiset(
                DSL.select(PROJECTS.ID)
                    .from(PROJECTS)
                    .where(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
                    .orderBy(PROJECTS.ID)
            )
            .convertFrom { result ->
              result
                  .map { it[PROJECTS.ID.asNonNullable()] }
                  .filter { user.canReadProject(it) }
                  .toSet()
            }

    return with(PARTICIPANTS) {
      dslContext
          .select(COHORT_ID, ID, NAME, projectIdsMultiset)
          .from(PARTICIPANTS)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { ParticipantModel.of(it, projectIdsMultiset) }
          .filter { user.canReadParticipant(it.id) }
    }
  }
}
