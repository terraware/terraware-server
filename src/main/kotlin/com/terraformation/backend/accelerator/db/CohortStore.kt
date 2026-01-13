package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.accelerator.model.ExistingCohortModel
import com.terraformation.backend.accelerator.model.NewCohortModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.tables.daos.CohortsDao
import com.terraformation.backend.db.accelerator.tables.pojos.CohortsRow
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException

@Named
class CohortStore(
    private val clock: InstantSource,
    private val cohortsDao: CohortsDao,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun fetchOneById(
      cohortId: CohortId,
      cohortDepth: CohortDepth = CohortDepth.Cohort,
  ): ExistingCohortModel {
    if (cohortDepth == CohortDepth.Project) {
      requirePermissions { readCohortProjects(cohortId) }
    }
    return fetch(COHORTS.ID.eq(cohortId), cohortDepth).firstOrNull()
        ?: throw CohortNotFoundException(cohortId)
  }

  fun fetchOneByName(
      name: String,
      cohortDepth: CohortDepth = CohortDepth.Cohort,
  ): ExistingCohortModel? {
    val cohort = fetch(COHORTS.NAME.eq(name), cohortDepth).firstOrNull() ?: return null

    if (cohortDepth == CohortDepth.Project) {
      requirePermissions { readCohortProjects(cohort.id) }
    }

    return cohort
  }

  fun findAll(
      cohortDepth: CohortDepth = CohortDepth.Cohort,
  ): List<ExistingCohortModel> {
    return fetch(null, cohortDepth)
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
            phaseId = model.phase,
        )

    cohortsDao.insert(row)
    val cohortModel = row.toModel()

    return cohortModel
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
      val hasProjects = dslContext.fetchExists(PROJECTS, PROJECTS.COHORT_ID.eq(cohortId))
      if (hasProjects) {
        throw CohortHasProjectsException(cohortId)
      } else {
        throw e
      }
    }
  }

  fun update(cohortId: CohortId, updateFunc: (ExistingCohortModel) -> ExistingCohortModel) {
    requirePermissions { updateCohort(cohortId) }

    val existing = fetchOneById(cohortId)
    val updated = updateFunc(existing)

    dslContext.transaction { _ ->
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

      if (existing.phase != updated.phase) {
        eventPublisher.publishEvent(CohortPhaseUpdatedEvent(cohortId, updated.phase))
      }
    }
  }

  private val projectIdsMultiset: Field<Set<ProjectId>> =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(PROJECTS)
                  .where(PROJECTS.COHORT_ID.eq(COHORTS.ID))
                  .orderBy(PROJECTS.ID)
          )
          .convertFrom { result -> result.map { it[PROJECTS.ID.asNonNullable()] }.toSet() }

  private fun fetch(
      condition: Condition?,
      cohortDepth: CohortDepth,
  ): List<ExistingCohortModel> {
    val user = currentUser()

    val projectIdsField =
        if (cohortDepth == CohortDepth.Project) {
          projectIdsMultiset
        } else {
          null
        }

    return with(COHORTS) {
      dslContext
          .select(COHORTS.asterisk(), projectIdsField)
          .from(COHORTS)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { ExistingCohortModel.of(it, projectIdsField) }
          .filter {
            user.canReadCohort(it.id) &&
                (projectIdsField == null || user.canReadCohortProjects(it.id))
          }
    }
  }
}
