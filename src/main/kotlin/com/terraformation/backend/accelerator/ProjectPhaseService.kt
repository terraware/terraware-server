package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.CohortProjectAddedEvent
import com.terraformation.backend.accelerator.event.CohortProjectRemovedEvent
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order

/**
 * Temporary event listeners to keep the phase values up to date on the projects table based on the
 * project's cohort. This will go away once we've removed cohorts.
 */
@Named
class ProjectPhaseService(private val dslContext: DSLContext) {
  @EventListener
  @Order(EVENT_LISTENER_ORDER)
  fun on(event: CohortProjectAddedEvent) {
    val phase = dslContext.fetchValue(COHORTS.PHASE_ID, COHORTS.ID.eq(event.cohortId))

    with(PROJECTS) {
      dslContext.update(PROJECTS).set(PHASE_ID, phase).where(ID.eq(event.projectId)).execute()
    }

    with(PROJECT_MODULES) {
      dslContext
          .insertInto(PROJECT_MODULES, PROJECT_ID, MODULE_ID, START_DATE, END_DATE, TITLE)
          .select(
              DSL.select(
                      DSL.value(event.projectId),
                      COHORT_MODULES.MODULE_ID,
                      COHORT_MODULES.START_DATE,
                      COHORT_MODULES.END_DATE,
                      COHORT_MODULES.TITLE,
                  )
                  .from(COHORT_MODULES)
                  .where(COHORT_MODULES.COHORT_ID.eq(event.cohortId))
          )
          .onConflictDoNothing()
          .execute()
    }
  }

  @EventListener
  @Order(EVENT_LISTENER_ORDER)
  fun on(event: CohortProjectRemovedEvent) {
    with(PROJECTS) {
      dslContext.update(PROJECTS).setNull(PHASE_ID).where(ID.eq(event.projectId)).execute()
    }

    with(PROJECT_MODULES) {
      dslContext
          .deleteFrom(PROJECT_MODULES)
          .where(PROJECT_ID.eq(event.projectId))
          .and(
              MODULE_ID.`in`(
                  DSL.select(COHORT_MODULES.MODULE_ID)
                      .from(COHORT_MODULES)
                      .where(COHORT_MODULES.COHORT_ID.eq(event.cohortId))
              )
          )
          .execute()
    }
  }

  @EventListener
  @Order(EVENT_LISTENER_ORDER)
  fun on(event: CohortPhaseUpdatedEvent) {
    with(PROJECTS) {
      dslContext
          .update(PROJECTS)
          .set(PHASE_ID, event.newPhase)
          .where(COHORT_ID.eq(event.cohortId))
          .execute()
    }
  }

  companion object {
    /**
     * Execution order of the event listeners in this service. Listeners for the same events on
     * other services that depend on the changes we apply in this service can configure themselves
     * with higher order values than this to guarantee they'll be called after the listeners here.
     */
    const val EVENT_LISTENER_ORDER = 10
  }
}
