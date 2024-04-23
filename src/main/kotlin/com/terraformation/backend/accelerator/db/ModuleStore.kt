package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.CohortModuleModel
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ModuleNotFoundException
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.EVENT_PROJECTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@Named
class ModuleStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneById(moduleId: ModuleId): ModuleModel {
    requirePermissions { readModuleDetails(moduleId) }

    return fetch(MODULES.ID.eq(moduleId)).firstOrNull() ?: throw ModuleNotFoundException(moduleId)
  }

  fun fetchOneByIdForProject(moduleId: ModuleId, projectId: ProjectId): ModuleModel {
    requirePermissions {
      readModule(moduleId)
      readProject(projectId)
    }

    return fetchForProject(projectId, MODULES.ID.eq(moduleId)).firstOrNull()
        ?: throw ModuleNotFoundException(moduleId)
  }

  fun fetchModulesForProject(projectId: ProjectId): List<ModuleModel> {
    requirePermissions { readProjectModules(projectId) }

    return fetchForProject(projectId)
  }

  fun fetchAllModules(): List<ModuleModel> {
    requirePermissions { manageModules() }
    return fetch()
  }

  private fun cohortsMultiset(): Field<List<CohortModuleModel>> {
    val projectsField =
        DSL.multiset(
                DSL.select(PROJECTS.ID)
                    .from(COHORTS)
                    .join(PARTICIPANTS)
                    .on(PARTICIPANTS.COHORT_ID.eq(COHORTS.ID))
                    .join(PROJECTS)
                    .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
                    .where(COHORTS.ID.eq(COHORT_MODULES.COHORT_ID)))
            .convertFrom { result -> result.map { it.value1() }.toSet() }
    return with(COHORT_MODULES) {
      DSL.multiset(
              DSL.select(asterisk(), projectsField)
                  .from(this)
                  .where(MODULE_ID.eq(MODULES.ID))
                  .orderBy(START_DATE))
          .convertFrom { result -> result.map { CohortModuleModel.of(it, projectsField) } }
    }
  }

  private fun eventsMultiset(
      projectId: ProjectId? = null
  ): Field<Map<EventType, List<EventModel>>> {
    val projectsField =
        if (projectId == null) {
          eventProjectsMultiset()
        } else {
          null
        }

    val projectEventCondition =
        projectId?.let {
          EVENTS.ID.`in`(
              DSL.select(EVENT_PROJECTS.EVENT_ID)
                  .from(EVENT_PROJECTS)
                  .where(EVENT_PROJECTS.PROJECT_ID.eq(it)))
        }

    return with(EVENTS) {
      DSL.multiset(
              DSL.select(asterisk(), projectsField)
                  .from(this)
                  .where(projectEventCondition)
                  .and(MODULE_ID.eq(MODULES.ID))
                  .orderBy(START_TIME))
          .convertFrom { result ->
            result.groupBy({ it[EVENTS.EVENT_TYPE_ID]!! }, { EventModel.of(it, projectsField) })
          }
    }
  }

  private fun fetch(condition: Condition? = null): List<ModuleModel> {
    val cohortsField = cohortsMultiset()
    val eventsField = eventsMultiset()
    return with(MODULES) {
      dslContext
          .select(asterisk(), cohortsField, eventsField)
          .from(this)
          .apply { condition?.let { where(it) } }
          .fetch { ModuleModel.of(it, eventsField, cohortsField) }
    }
  }

  private fun fetchForProject(
      projectId: ProjectId,
      condition: Condition? = null
  ): List<ModuleModel> {
    val eventsField = eventsMultiset(projectId)

    return with(MODULES) {
      dslContext
          .select(
              asterisk(),
              eventsField,
              COHORT_MODULES.COHORT_ID,
              COHORT_MODULES.START_DATE,
              COHORT_MODULES.END_DATE)
          .from(this)
          .join(COHORT_MODULES)
          .on(COHORT_MODULES.MODULE_ID.eq(ID))
          .join(PARTICIPANTS)
          .on(PARTICIPANTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
          .join(PROJECTS)
          .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
          .where(PROJECTS.ID.eq(projectId))
          .apply { condition?.let { and(condition) } }
          .orderBy(COHORT_MODULES.START_DATE)
          .fetch { ModuleModel.of(it, eventsField) }
    }
  }
}
