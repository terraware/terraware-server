package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import java.time.LocalDate
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ProjectModuleStore(
    private val dslContext: DSLContext,
) {
  fun fetch(
      projectId: ProjectId? = null,
      moduleId: ModuleId? = null,
  ): List<ModuleModel> {
    requirePermissions {
      when {
        projectId != null -> readProjectModules(projectId)
        else -> readAllAcceleratorDetails()
      }

      moduleId?.let { readModule(it) }
    }

    val condition =
        listOfNotNull(
                projectId?.let { PROJECT_MODULES.PROJECT_ID.eq(it) },
                moduleId?.let { PROJECT_MODULES.MODULE_ID.eq(it) },
            )
            .ifEmpty { listOf(DSL.trueCondition()) }

    return dslContext
        .select(MODULES.asterisk(), PROJECT_MODULES.asterisk())
        .from(MODULES)
        .join(PROJECT_MODULES)
        .on(MODULES.ID.eq(PROJECT_MODULES.MODULE_ID))
        .where(condition)
        .orderBy(
            PROJECT_MODULES.PROJECT_ID,
            PROJECT_MODULES.START_DATE,
            PROJECT_MODULES.END_DATE,
            MODULES.POSITION,
        )
        .fetch {
          ModuleModel.of(it)
              .copy(
                  projectId = it[PROJECT_MODULES.PROJECT_ID],
                  title = it[PROJECT_MODULES.TITLE],
                  startDate = it[PROJECT_MODULES.START_DATE],
                  endDate = it[PROJECT_MODULES.END_DATE],
              )
        }
        .groupBy { it.projectId!! }
        .filterKeys { currentUser().canReadProject(it) }
        .values
        .flatten()
  }

  fun assign(
      projectId: ProjectId,
      moduleId: ModuleId,
      title: String,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    requirePermissions { manageModules() }

    ensureProjectInPhase(projectId)

    with(PROJECT_MODULES) {
      dslContext
          .insertInto(PROJECT_MODULES)
          .set(END_DATE, endDate)
          .set(MODULE_ID, moduleId)
          .set(PROJECT_ID, projectId)
          .set(START_DATE, startDate)
          .set(TITLE, title)
          .onDuplicateKeyUpdate()
          .set(END_DATE, endDate)
          .set(START_DATE, startDate)
          .set(TITLE, title)
          .execute()
    }
  }

  fun remove(projectId: ProjectId, moduleId: ModuleId) {
    requirePermissions { manageModules() }

    ensureProjectInPhase(projectId)

    with(PROJECT_MODULES) {
      dslContext
          .deleteFrom(PROJECT_MODULES)
          .where(PROJECT_ID.eq(projectId))
          .and(MODULE_ID.eq(moduleId))
          .execute()
    }
  }

  private fun ensureProjectInPhase(projectId: ProjectId) {
    val projectPhases =
        dslContext
            .select(PROJECTS.PHASE_ID)
            .from(PROJECTS)
            .where(PROJECTS.ID.eq(projectId))
            .fetch(PROJECTS.PHASE_ID)
    if (projectPhases.isEmpty()) {
      throw ProjectNotFoundException(projectId)
    }
    if (projectPhases.first() == null) {
      throw ProjectNotInCohortPhaseException(projectId)
    }
  }
}
