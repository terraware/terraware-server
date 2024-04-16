package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.CohortModuleModel
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.EVENT_PROJECTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@Named
class ModuleStore(
    private val dslContext: DSLContext,
) {
  fun fetchModulesForProject(projectId: ProjectId): List<ModuleModel> {
    requirePermissions { readProjectModules(projectId) }

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
          .orderBy(COHORT_MODULES.START_DATE)
          .fetch { ModuleModel.of(it, eventsField) }
    }
  }

  fun fetchAllModules(): List<ModuleModel> {
    requirePermissions { manageModules() }

    val cohortsField = cohortsMultiset()
    val eventsField = eventsMultiset()
    return with(MODULES) {
      dslContext.select(asterisk(), cohortsField, eventsField).from(this).fetch {
        ModuleModel.of(it, eventsField, cohortsField)
      }
    }
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
    val projectCondition = projectId?.let { EVENT_PROJECTS.PROJECT_ID.eq(it) }
    val projectsField =
        if (projectId == null) {
          with(EVENT_PROJECTS) {
            DSL.multiset(DSL.select(PROJECT_ID).from(this).where(EVENT_ID.eq(EVENTS.ID)))
                .convertFrom { result -> result.map { it.value1() }.toSet() }
          }
        } else {
          null
        }
    return with(EVENTS) {
      DSL.multiset(
              DSL.select(asterisk(), projectsField)
                  .from(this)
                  .where(
                      ID.`in`(
                          DSL.select(EVENT_PROJECTS.EVENT_ID)
                              .from(EVENT_PROJECTS)
                              .where(projectCondition)))
                  .and(MODULE_ID.eq(MODULES.ID))
                  .orderBy(START_TIME))
          .convertFrom { result ->
            result.groupBy({ it[EVENTS.EVENT_TYPE_ID]!! }, { EventModel.of(it, projectsField) })
          }
    }
  }
}
