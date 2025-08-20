package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantsRow
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Field
import org.jooq.Record

data class ParticipantModel<ID : ParticipantId?>(
    val cohortId: CohortId?,
    val id: ID,
    val name: String,
    val projectIds: Set<ProjectId>,
) {
  companion object {
    fun of(record: Record, projectIdsField: Field<Set<ProjectId>>): ExistingParticipantModel {
      return ExistingParticipantModel(
          cohortId = record[PARTICIPANTS.COHORT_ID],
          id = record[PARTICIPANTS.ID]!!,
          name = record[PARTICIPANTS.NAME]!!,
          projectIds = record[projectIdsField],
      )
    }

    fun create(
        name: String,
        cohortId: CohortId? = null,
        projectIds: Set<ProjectId> = emptySet(),
    ): NewParticipantModel {
      return NewParticipantModel(
          cohortId = cohortId,
          id = null,
          name = name,
          projectIds = projectIds,
      )
    }
  }
}

typealias ExistingParticipantModel = ParticipantModel<ParticipantId>

typealias NewParticipantModel = ParticipantModel<Nothing?>

fun ParticipantsRow.toModel(projectIds: Set<ProjectId> = emptySet()): ExistingParticipantModel {
  return ExistingParticipantModel(
      cohortId = cohortId,
      id = id!!,
      name = name!!,
      projectIds = projectIds,
  )
}
