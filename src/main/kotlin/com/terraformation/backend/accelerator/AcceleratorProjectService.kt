package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.model.AcceleratorProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AcceleratorProjectNotFoundException
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class AcceleratorProjectService(
    private val dslContext: DSLContext,
) {
  fun fetchOneById(projectId: ProjectId): AcceleratorProjectModel {
    requirePermissions { readProjectAcceleratorDetails(projectId) }

    return fetch(PROJECTS.ID.eq(projectId)).singleOrNull()
        ?: throw AcceleratorProjectNotFoundException(projectId)
  }

  fun listAcceleratorProjects(): List<AcceleratorProjectModel> {
    requirePermissions { readAllAcceleratorDetails() }

    return fetch(DSL.trueCondition())
  }

  private fun fetch(condition: Condition): List<AcceleratorProjectModel> {
    val decisionsMultiset =
        DSL.multiset(
                DSL.select(
                        PROJECT_VOTE_DECISIONS.PHASE_ID.asNonNullable(),
                        PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID.asNonNullable(),
                    )
                    .from(PROJECT_VOTE_DECISIONS)
                    .where(PROJECT_VOTE_DECISIONS.PROJECT_ID.eq(PROJECTS.ID))
                    .and(PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID.isNotNull)
            )
            .convertFrom { result -> result.associate { it.value1() to it.value2() } }

    return dslContext
        .select(
            COHORTS.ID,
            COHORTS.NAME,
            COHORTS.PHASE_ID,
            PROJECTS.ID,
            PROJECTS.NAME,
            decisionsMultiset,
        )
        .from(PROJECTS)
        .join(COHORTS)
        .on(PROJECTS.COHORT_ID.eq(COHORTS.ID))
        .where(condition)
        .orderBy(COHORTS.ID, PROJECTS.ID)
        .fetch { AcceleratorProjectModel.of(it, decisionsMultiset) }
  }
}
