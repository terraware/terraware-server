package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.CohortModel
import com.terraformation.backend.accelerator.model.ExistingCohortModel
import com.terraformation.backend.accelerator.model.NewCohortModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.CohortId
import com.terraformation.backend.db.default_schema.tables.daos.CohortsDao
import com.terraformation.backend.db.default_schema.tables.pojos.CohortsRow
import com.terraformation.backend.db.default_schema.tables.references.COHORTS
import com.terraformation.backend.db.default_schema.tables.references.PARTICIPANTS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DataIntegrityViolationException

@Named
class CohortStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val cohortsDao: CohortsDao,
) {
  fun fetchOneById(cohortId: CohortId): ExistingCohortModel {
    return fetch(COHORTS.ID.eq(cohortId)).firstOrNull() ?: throw CohortNotFoundException(cohortId)
  }

  fun findAll(): List<ExistingCohortModel> {
    return fetch(null)
  }

  fun create(model: NewCohortModel): ExistingCohortModel {
    requirePermissions { createCohort() }

    val now = clock.instant()
    val userId = currentUser().userId

    val row =
        CohortsRow(
            createdBy = userId,
            createdTime = now,
            modifiedBy = userId,
            modifiedTime = now,
            name = model.name,
            phaseId = model.phase)

    cohortsDao.insert(row)

    return row.toModel()
  }

  fun delete(cohortId: CohortId) {
    requirePermissions { deleteCohort(cohortId) }

    try {
      dslContext.transaction { _ ->
        val rowsDeleted = dslContext.deleteFrom(COHORTS).where(COHORTS.ID.eq(cohortId)).execute()

        if (rowsDeleted == 0) {
          throw CohortNotFoundException(cohortId)
        }
      }
    } catch (e: DataIntegrityViolationException) {
      val hasParticipants =
          dslContext.fetchExists(PARTICIPANTS, PARTICIPANTS.COHORT_ID.eq(cohortId))
      if (hasParticipants) {
        throw CohortHasParticipantsException(cohortId)
      } else {
        throw e
      }
    }
  }

  fun update(cohortId: CohortId, updateFunc: (ExistingCohortModel) -> ExistingCohortModel) {
    requirePermissions { updateCohort(cohortId) }

    val existing = fetchOneById(cohortId)
    val updated = updateFunc(existing)

    val rowsUpdated =
        with(COHORTS) {
          dslContext
              .update(COHORTS)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .set(NAME, updated.name)
              .set(PHASE_ID, updated.phase)
              .where(ID.eq(cohortId))
              .execute()
        }

    if (rowsUpdated < 1) {
      throw CohortNotFoundException(cohortId)
    }
  }

  private fun fetch(condition: Condition?): List<ExistingCohortModel> {
    val user = currentUser()

    val participantIdsMultiset =
        DSL.multiset(
                DSL.select(PARTICIPANTS.ID)
                    .from(PARTICIPANTS)
                    .where(PARTICIPANTS.COHORT_ID.eq(COHORTS.ID))
                    .orderBy(PARTICIPANTS.ID))
            .convertFrom { result -> result.map { it[PARTICIPANTS.ID.asNonNullable()] } }

    return with(COHORTS) {
      dslContext
          .select(ID, NAME, PHASE_ID, participantIdsMultiset)
          .from(COHORTS)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { CohortModel.of(it, participantIdsMultiset) }
          .filter { user.canReadCohort(it.id) }
    }
  }
}
