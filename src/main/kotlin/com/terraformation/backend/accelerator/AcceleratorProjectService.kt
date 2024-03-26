package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.model.AcceleratorProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class AcceleratorProjectService(
    private val dslContext: DSLContext,
) {
  fun listAcceleratorProjects(): List<AcceleratorProjectModel> {
    requirePermissions { readAllAcceleratorDetails() }
    return dslContext
        .select(
            COHORTS.ID,
            COHORTS.NAME,
            COHORTS.PHASE_ID,
            PARTICIPANTS.ID,
            PARTICIPANTS.NAME,
            PROJECTS.ID,
            PROJECTS.NAME,
            PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID)
        .from(PROJECTS)
        .join(PARTICIPANTS)
        .on(PARTICIPANTS.ID.eq(PROJECTS.PARTICIPANT_ID))
        .join(COHORTS)
        .on(PARTICIPANTS.COHORT_ID.eq(COHORTS.ID))
        .leftJoin(PROJECT_VOTE_DECISIONS)
        .on(PROJECT_VOTE_DECISIONS.PROJECT_ID.eq(PROJECTS.ID))
        .orderBy(COHORTS.ID, PROJECTS.ID)
        .fetch { AcceleratorProjectModel.of(it) }
  }
}
