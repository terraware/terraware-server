package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import java.time.LocalDate
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectOnConditionStep
import org.jooq.impl.DSL

@Named
class CohortModuleStore(
    private val dslContext: DSLContext,
) {
  fun fetch(
      cohortId: CohortId? = null,
      participantId: ParticipantId? = null,
      projectId: ProjectId? = null,
      moduleId: ModuleId? = null,
  ): List<ModuleModel> {
    requirePermissions {
      when {
        projectId != null -> readProjectModules(projectId)
        participantId != null -> readParticipant(participantId)
        cohortId != null -> readCohort(cohortId)
        else -> readCohorts()
      }

      moduleId?.let { readModule(it) }
    }

    val condition =
        DSL.and(
            when {
              projectId != null -> PROJECTS.ID.eq(projectId)
              participantId != null -> PARTICIPANTS.ID.eq(participantId)
              cohortId != null -> COHORT_MODULES.COHORT_ID.eq(cohortId)
              else -> null
            },
            moduleId?.let { MODULES.ID.eq(moduleId) },
        )

    val joinForVisibility = { query: SelectOnConditionStep<Record> ->
      when {
        projectId != null ->
            query
                .join(PARTICIPANTS)
                .on(PARTICIPANTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
                .join(PROJECTS)
                .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
        participantId != null ->
            query.join(PARTICIPANTS).on(PARTICIPANTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
        else -> query
      }
    }

    return fetch(condition, joinForVisibility)
  }

  fun assign(
      cohortId: CohortId,
      moduleId: ModuleId,
      title: String,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    requirePermissions { manageModules() }

    with(COHORT_MODULES) {
      dslContext
          .insertInto(this)
          .set(COHORT_ID, cohortId)
          .set(MODULE_ID, moduleId)
          .set(TITLE, title)
          .set(START_DATE, startDate)
          .set(END_DATE, endDate)
          .onDuplicateKeyUpdate()
          .set(TITLE, title)
          .set(START_DATE, startDate)
          .set(END_DATE, endDate)
          .execute()
    }
  }

  fun assign(
      projectId: ProjectId,
      moduleId: ModuleId,
      title: String,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    assign(fetchCohortId(projectId), moduleId, title, startDate, endDate)
  }

  fun remove(cohortId: CohortId, moduleId: ModuleId) {
    requirePermissions { manageModules() }

    with(COHORT_MODULES) {
      dslContext
          .deleteFrom(this)
          .where(COHORT_ID.eq(cohortId))
          .and(MODULE_ID.eq(moduleId))
          .execute()
    }
  }

  fun remove(projectId: ProjectId, moduleId: ModuleId) {
    remove(fetchCohortId(projectId), moduleId)
  }

  private fun fetch(
      condition: Condition? = null,
      joinForVisibility: (SelectOnConditionStep<Record>) -> SelectOnConditionStep<Record>,
  ): List<ModuleModel> {
    val query =
        dslContext
            .select(
                MODULES.asterisk(),
                COHORT_MODULES.COHORT_ID,
                COHORT_MODULES.TITLE,
                COHORT_MODULES.START_DATE,
                COHORT_MODULES.END_DATE,
            )
            .from(MODULES)
            .join(COHORT_MODULES)
            .on(COHORT_MODULES.MODULE_ID.eq(MODULES.ID))

    return joinForVisibility(query)
        .apply { condition?.let { where(condition) } }
        .orderBy(
            COHORT_MODULES.COHORT_ID,
            COHORT_MODULES.START_DATE,
            COHORT_MODULES.END_DATE,
            MODULES.POSITION,
        )
        .fetch {
          ModuleModel.of(it)
              .copy(
                  cohortId = it[COHORT_MODULES.COHORT_ID],
                  title = it[COHORT_MODULES.TITLE],
                  startDate = it[COHORT_MODULES.START_DATE],
                  endDate = it[COHORT_MODULES.END_DATE],
              )
        }
        .filter { currentUser().canReadCohort(it.cohortId!!) }
  }

  private fun fetchCohortId(projectId: ProjectId): CohortId {
    return dslContext
        .select(PROJECTS.COHORT_ID)
        .from(PROJECTS)
        .where(PROJECTS.ID.eq(projectId))
        .fetch(PROJECTS.COHORT_ID)
        .firstOrNull() ?: throw ProjectNotInCohortException(projectId)
  }
}
