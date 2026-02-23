package com.terraformation.backend.accelerator.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class ProjectPhaseFetcher(private val dslContext: DSLContext) {
  /** Returns the current phase of a project, or null if the project has no phase or application. */
  fun getProjectPhase(projectId: ProjectId): AcceleratorPhase? {
    requirePermissions { readProject(projectId) }

    return dslContext
        .select(PROJECTS.PHASE_ID, APPLICATIONS.APPLICATION_STATUS_ID)
        .from(PROJECTS)
        .leftJoin(APPLICATIONS)
        .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
        .where(PROJECTS.ID.eq(projectId))
        .fetchOne { (projectPhase, applicationStatus) ->
          projectPhase
              ?: when (applicationStatus) {
                ApplicationStatus.NotSubmitted,
                ApplicationStatus.FailedPreScreen,
                ApplicationStatus.PassedPreScreen -> AcceleratorPhase.PreScreen

                null -> null
                else -> AcceleratorPhase.Application
              }
        }
  }

  /**
   * Ensures the project is in the specified phase, or that the project has an application and is in
   * phase 0.
   *
   * @throws ProjectNotInAcceleratorPhaseException The project is not in the requested phase.
   */
  fun ensureProjectPhase(projectId: ProjectId, phase: AcceleratorPhase) {
    val currentPhase = getProjectPhase(projectId)

    val samePhase =
        when (phase) {
          AcceleratorPhase.Phase0DueDiligence ->
              // Application phases are considered to be part of phase 0.
              currentPhase == AcceleratorPhase.PreScreen ||
                  currentPhase == AcceleratorPhase.Application ||
                  currentPhase == phase
          else -> currentPhase == phase
        }

    if (!samePhase) {
      throw ProjectNotInAcceleratorPhaseException(projectId, phase)
    }
  }
}
