package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingParticipantModel
import com.terraformation.backend.accelerator.model.NewParticipantModel
import com.terraformation.backend.accelerator.model.ParticipantModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ParticipantId
import com.terraformation.backend.db.default_schema.tables.daos.ParticipantsDao
import com.terraformation.backend.db.default_schema.tables.pojos.ParticipantsRow
import com.terraformation.backend.db.default_schema.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DataIntegrityViolationException

@Named
class ParticipantStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
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

    val row =
        ParticipantsRow(
            createdBy = userId,
            createdTime = now,
            modifiedBy = userId,
            modifiedTime = now,
            name = model.name,
        )

    participantsDao.insert(row)

    return row.toModel()
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

  fun update(
      participantId: ParticipantId,
      updateFunc: (ExistingParticipantModel) -> ExistingParticipantModel
  ) {
    requirePermissions { updateParticipant(participantId) }

    val existing = fetchOneById(participantId)
    val updated = updateFunc(existing)

    val rowsUpdated =
        with(PARTICIPANTS) {
          dslContext
              .update(PARTICIPANTS)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .set(NAME, updated.name)
              .where(ID.eq(participantId))
              .execute()
        }

    if (rowsUpdated < 1) {
      throw ParticipantNotFoundException(participantId)
    }
  }

  private fun fetch(condition: Condition?): List<ExistingParticipantModel> {
    val user = currentUser()

    val projectIdsMultiset =
        DSL.multiset(
                DSL.select(PROJECTS.ID)
                    .from(PROJECTS)
                    .where(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
                    .orderBy(PROJECTS.ID))
            .convertFrom { result -> result.map { it[PROJECTS.ID.asNonNullable()] } }

    return with(PARTICIPANTS) {
      dslContext
          .select(ID, NAME, projectIdsMultiset)
          .from(PARTICIPANTS)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { ParticipantModel.of(it, projectIdsMultiset) }
          .filter { user.canReadParticipant(it.id) }
    }
  }
}
