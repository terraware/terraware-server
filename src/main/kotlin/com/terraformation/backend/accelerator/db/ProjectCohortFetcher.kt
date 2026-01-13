package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ProjectCohortData
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class ProjectCohortFetcher(private val dslContext: DSLContext) {
  fun fetchCohortData(projectId: ProjectId): ProjectCohortData? {
    requirePermissions { readProject(projectId) }

    return dslContext
        .select(COHORTS.ID, COHORTS.PHASE_ID, APPLICATIONS.APPLICATION_STATUS_ID)
        .from(PROJECTS)
        .leftJoin(COHORTS)
        .on(PROJECTS.COHORT_ID.eq(COHORTS.ID))
        .leftJoin(APPLICATIONS)
        .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
        .where(PROJECTS.ID.eq(projectId))
        .fetchOne { (cohortId, cohortPhase, applicationStatus) ->
          if (cohortId != null && cohortPhase != null) {
            ProjectCohortData(cohortId, cohortPhase)
          } else {
            when (applicationStatus) {
              ApplicationStatus.NotSubmitted,
              ApplicationStatus.FailedPreScreen,
              ApplicationStatus.PassedPreScreen ->
                  ProjectCohortData(cohortPhase = CohortPhase.PreScreen)
              null -> null
              else -> ProjectCohortData(cohortPhase = CohortPhase.Application)
            }
          }
        }
  }

  /** Returns the current phase of a project, or null if the project is not in a cohort. */
  fun getProjectPhase(projectId: ProjectId): CohortPhase? {
    return fetchCohortData(projectId)?.cohortPhase
  }

  /**
   * Ensures the project's cohort is in the specified phase, or that the project has an application
   * and is in phase 0.
   *
   * @throws ProjectNotInCohortException The project is not in a cohort, and the project has no
   *   application.
   * @throws ProjectNotInCohortPhaseException The project's cohort is in a different phase than the
   *   specified one.
   */
  fun ensureProjectPhase(projectId: ProjectId, phase: CohortPhase) {
    val currentPhase = getProjectPhase(projectId) ?: throw ProjectNotInCohortException(projectId)

    val samePhase =
        when (phase) {
          CohortPhase.Phase0DueDiligence ->
              // Application phases are considered to be part of phase 0.
              currentPhase == CohortPhase.PreScreen ||
                  currentPhase == CohortPhase.Application ||
                  currentPhase == phase
          else -> currentPhase == phase
        }

    if (!samePhase) {
      throw ProjectNotInCohortPhaseException(projectId, phase)
    }
  }
}
