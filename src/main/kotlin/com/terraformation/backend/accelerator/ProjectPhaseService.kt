package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.CohortProjectAddedEvent
import com.terraformation.backend.accelerator.event.CohortProjectRemovedEvent
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/**
 * Temporary event listeners to keep the phase values up to date on the projects table based on the
 * project's cohort. This will go away once we've removed cohorts.
 */
@Named
class ProjectPhaseService(private val dslContext: DSLContext) {
  @EventListener
  fun on(event: CohortProjectAddedEvent) {
    val phase = dslContext.fetchValue(COHORTS.PHASE_ID, COHORTS.ID.eq(event.cohortId))

    with(PROJECTS) {
      dslContext.update(PROJECTS).set(PHASE_ID, phase).where(ID.eq(event.projectId)).execute()
    }
  }

  @EventListener
  fun on(event: CohortProjectRemovedEvent) {
    with(PROJECTS) {
      dslContext.update(PROJECTS).setNull(PHASE_ID).where(ID.eq(event.projectId)).execute()
    }
  }

  @EventListener
  fun on(event: CohortPhaseUpdatedEvent) {
    with(PROJECTS) {
      dslContext
          .update(PROJECTS)
          .set(PHASE_ID, event.newPhase)
          .where(COHORT_ID.eq(event.cohortId))
          .execute()
    }
  }
}
