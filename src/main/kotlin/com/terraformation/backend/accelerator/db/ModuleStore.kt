package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.ModuleDeliverableModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
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

  fun fetchCohortPhase(moduleId: ModuleId): CohortPhase {
    return dslContext
        .select(MODULES.PHASE_ID)
        .from(MODULES)
        .where(MODULES.ID.eq(moduleId))
        .fetchOne(MODULES.PHASE_ID) ?: throw ModuleNotFoundException(moduleId)
  }

  private fun deliverablesMultiset(): Field<List<ModuleDeliverableModel>> {
    return with(DELIVERABLES) {
      DSL.multiset(DSL.selectFrom(this).where(MODULE_ID.eq(MODULES.ID)).orderBy(POSITION))
          .convertFrom { result -> result.map { ModuleDeliverableModel.of(it) } }
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
    val deliverablesField = deliverablesMultiset()
    val eventsField = eventsMultiset()
    return with(MODULES) {
      dslContext
          .select(asterisk(), deliverablesField, eventsField)
          .from(this)
          .apply { condition?.let { where(it) } }
          .fetch { ModuleModel.of(it, deliverablesField, eventsField) }
    }
  }

  private fun fetchForProject(
      projectId: ProjectId,
      condition: Condition? = null
  ): List<ModuleModel> {
    val deliverablesField = deliverablesMultiset()
    val eventsField = eventsMultiset(projectId)

    return with(MODULES) {
      dslContext
          .select(
              asterisk(),
              deliverablesField,
              eventsField,
              COHORT_MODULES.COHORT_ID,
              COHORT_MODULES.MODULE_ID,
              COHORT_MODULES.TITLE,
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
          .fetch { ModuleModel.of(it, deliverablesField, eventsField) }
    }
  }
}
