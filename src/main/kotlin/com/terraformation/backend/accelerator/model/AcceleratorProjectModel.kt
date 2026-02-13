package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import org.jooq.Field
import org.jooq.Record

data class AcceleratorProjectModel(
    val cohortId: CohortId,
    val cohortName: String,
    val phase: CohortPhase,
    val phaseName: String = phase.getDisplayName(null),
    val projectId: ProjectId,
    val projectName: String,
    val voteDecisions: Map<CohortPhase, VoteOption> = emptyMap(),
) {
  companion object {
    fun of(
        record: Record,
        decisionsField: Field<Map<CohortPhase, VoteOption>>,
    ): AcceleratorProjectModel {
      return AcceleratorProjectModel(
          cohortId = record[COHORTS.ID]!!,
          cohortName = record[COHORTS.NAME]!!,
          phase = record[PROJECTS.PHASE_ID]!!,
          projectId = record[PROJECTS.ID]!!,
          projectName = record[PROJECTS.NAME]!!,
          voteDecisions = record[decisionsField] ?: emptyMap(),
      )
    }
  }
}
