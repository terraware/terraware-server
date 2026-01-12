package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.pojos.CohortsRow
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class NewCohortModel(
    val name: String,
    val phase: CohortPhase,
    val projectIds: Set<ProjectId> = emptySet(),
)

data class ExistingCohortModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val id: CohortId,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val name: String,
    val phase: CohortPhase,
    val projectIds: Set<ProjectId>,
) {
  companion object {
    fun of(
        record: Record,
        projectIdsField: Field<Set<ProjectId>>? = null,
    ): ExistingCohortModel {
      return ExistingCohortModel(
          createdBy = record[COHORTS.CREATED_BY]!!,
          createdTime = record[COHORTS.CREATED_TIME]!!,
          id = record[COHORTS.ID]!!,
          modifiedBy = record[COHORTS.MODIFIED_BY]!!,
          modifiedTime = record[COHORTS.MODIFIED_TIME]!!,
          name = record[COHORTS.NAME]!!,
          phase = record[COHORTS.PHASE_ID]!!,
          projectIds = projectIdsField?.let { record[it] } ?: emptySet(),
      )
    }

    fun create(name: String, phase: CohortPhase): NewCohortModel {
      return NewCohortModel(
          name = name,
          phase = phase,
          projectIds = emptySet(),
      )
    }
  }
}

fun CohortsRow.toModel(
    projectIds: Set<ProjectId> = emptySet(),
): ExistingCohortModel {
  return ExistingCohortModel(
      createdBy = createdBy!!,
      createdTime = createdTime!!,
      id = id!!,
      modifiedBy = modifiedBy!!,
      modifiedTime = modifiedTime!!,
      name = name!!,
      phase = phaseId!!,
      projectIds = projectIds,
  )
}
