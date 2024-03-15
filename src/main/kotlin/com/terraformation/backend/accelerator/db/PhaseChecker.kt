package com.terraformation.backend.accelerator.db

import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class PhaseChecker(private val dslContext: DSLContext) {
  /**
   * Returns the current phase of a project, or null if the project is not in a participant that is
   * in a cohort.
   */
  fun getProjectPhase(projectId: ProjectId): CohortPhase? {
    return dslContext
        .select(COHORTS.PHASE_ID)
        .from(PROJECTS)
        .join(PARTICIPANTS)
        .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
        .join(COHORTS)
        .on(PARTICIPANTS.COHORT_ID.eq(COHORTS.ID))
        .where(PROJECTS.ID.eq(projectId))
        .fetchOne(COHORTS.PHASE_ID)
  }

  /**
   * Ensures the project's participant's cohort is in the specified phase.
   *
   * @throws ProjectNotInCohortException The project is not in a participant, or its participant is
   *   not in a cohort.
   * @throws ProjectNotInCohortPhaseException The project's participant's cohort is in a different
   *   phase than the specified one.
   */
  fun ensureProjectPhase(projectId: ProjectId, phase: CohortPhase) {
    val currentPhase = getProjectPhase(projectId) ?: throw ProjectNotInCohortException(projectId)

    if (currentPhase != phase) {
      throw ProjectNotInCohortPhaseException(projectId, phase)
    }
  }
}
