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
    val projectIds: List<ProjectId>,
) {
  companion object {
    fun of(record: Record, projectIdsField: Field<List<ProjectId>>): ExistingParticipantModel {
      return ExistingParticipantModel(
          cohortId = record[PARTICIPANTS.COHORT_ID],
          id = record[PARTICIPANTS.ID]!!,
          name = record[PARTICIPANTS.NAME]!!,
          projectIds = record[projectIdsField],
      )
    }

    fun create(name: String): NewParticipantModel {
      return NewParticipantModel(
          cohortId = null,
          id = null,
          name = name,
          projectIds = emptyList(),
      )
    }
  }
}

typealias ExistingParticipantModel = ParticipantModel<ParticipantId>

typealias NewParticipantModel = ParticipantModel<Nothing?>

fun ParticipantsRow.toModel(projectIds: List<ProjectId> = emptyList()): ExistingParticipantModel {
  return ExistingParticipantModel(
      cohortId = cohortId,
      id = id!!,
      name = name!!,
      projectIds = projectIds,
  )
}
