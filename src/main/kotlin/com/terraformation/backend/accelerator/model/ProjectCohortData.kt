package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import org.jooq.Record

data class ProjectCohortData(
    val cohortId: CohortId,
    val cohortPhase: CohortPhase,
) {
  companion object {
    fun of(record: Record): ProjectCohortData {
      return ProjectCohortData(
          cohortId = record[COHORTS.ID]!!,
          cohortPhase = record[COHORTS.PHASE_ID]!!,
      )
    }
  }
}
